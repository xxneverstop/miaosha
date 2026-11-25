package com.imooc.miaoshaproject.service.impl;

import com.imooc.miaoshaproject.dao.ItemStockDOMapper;
import com.imooc.miaoshaproject.dao.StockConsumeLogMapper;
import com.imooc.miaoshaproject.dao.StockLogDOMapper;
import com.imooc.miaoshaproject.dataobject.StockConsumeLogDO;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 用独立的本机 MySQL 验证库存消费事务。stock_log 保存上游的库存流水和下单状态，
 * stock_consume_log 保存已经成功扣库的流水编号，item_stock 保存商品的 MySQL 库存。
 * 测试直接调用带事务代理的 StockConsumeService，通过读取真实数据验证重复消费和失败回滚。
 */
public class StockConsumeMySqlIT {
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;
    private static StockLogDOMapper stockLogMapper;
    private static StockConsumeLogMapper consumeLogMapper;
    private static ItemStockDOMapper itemStockMapper;

    private final List<String> stockLogIds = new ArrayList<>();
    private Integer itemId;

    // 检查专用测试库，再用同一个数据源创建真实 Mapper 和事务管理器。
    @BeforeClass
    public static void connectToTestDatabase() throws Exception {
        String jdbcUrl = requiredEnvironmentVariable("MIAOSHA_STOCK_IT_JDBC_URL");
        String database = requireLocalTestDatabase(jdbcUrl);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl,
                requiredEnvironmentVariable("MIAOSHA_STOCK_IT_USERNAME"),
                requiredEnvironmentVariable("MIAOSHA_STOCK_IT_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        // 核对连接实际选中的库，避免把测试写入和清理操作跑到其他库。
        assertEquals("Integration test must use its dedicated database", database,
                jdbc.queryForObject("SELECT DATABASE()", String.class));

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new Resource[]{new ClassPathResource("mapping/StockLogDOMapper.xml"),
                new ClassPathResource("mapping/StockConsumeLogMapper.xml"),
                new ClassPathResource("mapping/ItemStockDOMapper.xml")});
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        stockLogMapper = session.getMapper(StockLogDOMapper.class);
        consumeLogMapper = session.getMapper(StockConsumeLogMapper.class);
        itemStockMapper = session.getMapper(ItemStockDOMapper.class);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    // 每条用例结束时按本轮生成的流水号和商品 ID 清理数据，保留库中其他记录。
    @After
    public void deleteFixtures() {
        for (String stockLogId : stockLogIds) {
            jdbc.update("DELETE FROM stock_consume_log WHERE stock_log_id = ?", stockLogId);
            jdbc.update("DELETE FROM stock_log WHERE stock_log_id = ?", stockLogId);
        }
        if (itemId != null) {
            jdbc.update("DELETE FROM item_stock WHERE item_id = ?", itemId);
        }
    }

    // 首次消费、同一流水重放和不同流水消费分别验证扣库、去重和去重粒度。
    @Test
    public void firstCommitAndOneHundredReplaysDeductOncePerLog() {
        // 商品初始库存是 10，stock_log 表示本次已成功下单 1 件。
        createStock(10);
        String stockLogId = createStockLog(1, 2);
        StockConsumeService service = transactionalService(consumeLogMapper, itemStockMapper);

        service.consumeOnce(stockLogId, itemId, 1);
        assertStockAndReceipt(stockLogId, 9, 1);
        // 用完全相同的流水号、商品和数量重放，预期库存仍为 9，消费记录仍为 1 条。
        for (int replay = 0; replay < 100; replay++) {
            service.consumeOnce(stockLogId, itemId, 1);
        }
        assertStockAndReceipt(stockLogId, 9, 1);

        // 同一商品换一个流水号仍应扣库，验证去重范围是单笔库存流水。
        String secondLogId = createStockLog(2, 2);
        service.consumeOnce(secondLogId, itemId, 2);
        assertStockAndReceipt(secondLogId, 7, 1);
    }

    // 模拟并发收到同一条库存消息，验证唯一键在事务竞争时也能防止重复扣库。
    @Test
    public void oneHundredConcurrentDeliveriesDeductOnce() throws Exception {
        // 100 次调用共享这一个已提交流水，每次都请求扣同一商品的 1 件库存。
        createStock(10);
        String stockLogId = createStockLog(1, 2);
        int workerCount = 8;
        CountDownLatch atInsert = new CountDownLatch(workerCount);
        StockConsumeLogMapper competingInserts = new StockConsumeLogMapper() {
            // 只在写消费记录前同步首批线程，实际 INSERT 仍交给 MySQL 执行。
            @Override
            public int insert(StockConsumeLogDO record) {
                atInsert.countDown();
                await(atInsert);
                return consumeLogMapper.insert(record);
            }

            // 重复键分支也使用真实的锁定查询，读取另一笔事务提交的消费记录。
            @Override
            public StockConsumeLogDO selectByStockLogIdForShare(String id) {
                return consumeLogMapper.selectByStockLogIdForShare(id);
            }
        };
        StockConsumeService service = transactionalService(competingInserts, itemStockMapper);
        ExecutorService workers = Executors.newFixedThreadPool(workerCount);
        try {
            List<Future<?>> deliveries = new ArrayList<>();
            for (int delivery = 0; delivery < 100; delivery++) {
                deliveries.add(workers.submit(() -> service.consumeOnce(stockLogId, itemId, 1)));
            }
            // 等待所有调用完成，Future 会把工作线程里的异常带回测试线程。
            for (Future<?> delivery : deliveries) {
                delivery.get(20, TimeUnit.SECONDS);
            }
        } finally {
            workers.shutdownNow();
            assertTrue("Stock consumption test workers did not stop", workers.awaitTermination(20, TimeUnit.SECONDS));
        }
        assertStockAndReceipt(stockLogId, 9, 1);
    }

    // 消费记录写入后、扣库前发生故障时，应撤销记录，让同一流水还能重试。
    @Test
    public void failureAfterReceiptInsertRollsBackAndCanRetry() {
        RuntimeException fault = new IllegalStateException("Injected failure after receipt INSERT");
        StockConsumeLogMapper failingMapper = failAfterWrite(StockConsumeLogMapper.class, consumeLogMapper, "insert", fault);
        assertRollbackAndRetry(transactionalService(failingMapper, itemStockMapper), fault);
    }

    // item_stock 已被更新后再失败，库存和消费记录都应恢复到事务开始前的状态。
    @Test
    public void failureAfterStockUpdateRollsBackBothWritesAndCanRetry() {
        RuntimeException fault = new IllegalStateException("Injected failure after item_stock UPDATE");
        ItemStockDOMapper failingMapper = failAfterWrite(ItemStockDOMapper.class, itemStockMapper, "decreaseStock", fault);
        assertRollbackAndRetry(transactionalService(consumeLogMapper, failingMapper), fault);
    }

    // 扣库 SQL 更新 0 行时，整笔消费应失败，并撤销已插入的消费记录。
    @Test
    public void zeroRowsUpdatedLeavesNoReceiptAndCanRetry() {
        // 库存为 0，却申请扣 1 件，触发扣库 SQL 的库存不足条件。
        createStock(0);
        String stockLogId = createStockLog(1, 2);
        StockConsumeService service = transactionalService(consumeLogMapper, itemStockMapper);

        expectFailure(() -> service.consumeOnce(stockLogId, itemId, 1));
        assertStockAndReceipt(stockLogId, 0, 0);

        // 把测试库存调整到 10 后重试同一流水，确认这次能扣至 9 并写入唯一一条消费记录。
        jdbc.update("UPDATE item_stock SET stock = 10 WHERE item_id = ?", itemId);
        service.consumeOnce(stockLogId, itemId, 1);
        assertStockAndReceipt(stockLogId, 9, 1);
    }

    // 验证只有已提交的 stock_log 才能用于扣库，消息数量也必须和库存流水一致。
    @Test
    public void rejectsUncommittedSourceAndPayloadMismatches() {
        createStock(10);
        // stock_log 状态 1 表示待确认，状态 2 表示下单事务已提交，仍须核对数量。
        String pendingLogId = createStockLog(1, 1);
        String mismatchedLogId = createStockLog(1, 2);
        StockConsumeService service = transactionalService(consumeLogMapper, itemStockMapper);

        // 第一笔的数量正确但上游未提交，第二笔已提交但消息改成了 2 件。
        expectFailure(() -> service.consumeOnce(pendingLogId, itemId, 1));
        assertStockAndReceipt(pendingLogId, 10, 0);
        expectFailure(() -> service.consumeOnce(mismatchedLogId, itemId, 2));
        assertStockAndReceipt(mismatchedLogId, 10, 0);
    }

    // 主键重复后，还须核对旧消费记录的商品和数量，避免把冲突误认为处理成功。
    @Test
    public void matchingSourceCannotAcknowledgeAConflictingReceipt() {
        createStock(10);
        String stockLogId = createStockLog(1, 2);
        // 原始 stock_log 和本次消息都是 1 件，故意先放入同一流水号的 2 件消费记录。
        jdbc.update("INSERT INTO stock_consume_log (stock_log_id, item_id, amount) VALUES (?, ?, ?)",
                stockLogId, itemId, 2);
        StockConsumeService service = transactionalService(consumeLogMapper, itemStockMapper);

        expectFailure(() -> service.consumeOnce(stockLogId, itemId, 1));
        assertStockAndReceipt(stockLogId, 10, 1);
    }

    // 共用回滚验收流程。先确认注入点确实触发，再用正常服务重试同一流水。
    private void assertRollbackAndRetry(StockConsumeService failingService, RuntimeException fault) {
        createStock(10);
        String stockLogId = createStockLog(1, 2);
        assertSame(fault, expectFailure(() -> failingService.consumeOnce(stockLogId, itemId, 1)));
        // 从事务外直接查库，确认库存回到 10，本次流水的消费记录为 0 条。
        assertStockAndReceipt(stockLogId, 10, 0);

        // 去掉注入的故障，用同一流水重试，确认回滚后没有留下去重记录拦住扣库。
        transactionalService(consumeLogMapper, itemStockMapper).consumeOnce(stockLogId, itemId, 1);
        assertStockAndReceipt(stockLogId, 9, 1);
    }

    // 在 item_stock 写入本轮商品的初始库存，用随机高位 ID 降低与既有样例数据撞号的概率。
    private void createStock(int stock) {
        int fixtureItemId = ThreadLocalRandom.current().nextInt(1_000_000_000, Integer.MAX_VALUE);
        jdbc.update("INSERT INTO item_stock (item_id, stock) VALUES (?, ?)", fixtureItemId, stock);
        itemId = fixtureItemId;
    }

    // 在 stock_log 模拟上游写入的流水。amount 是购买量，status 用来设置本用例的下单状态。
    private String createStockLog(int amount, int status) {
        String stockLogId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO stock_log (stock_log_id, item_id, amount, status) VALUES (?, ?, ?, ?)",
                stockLogId, itemId, amount, status);
        stockLogIds.add(stockLogId);
        return stockLogId;
    }

    // 用独立 SQL 同时读取商品库存和该流水的消费记录数，验证提交后的数据库结果。
    private void assertStockAndReceipt(String stockLogId, int stock, int receipts) {
        assertEquals(stock, jdbc.queryForObject("SELECT stock FROM item_stock WHERE item_id = ?", Integer.class, itemId).intValue());
        assertEquals(receipts, jdbc.queryForObject("SELECT COUNT(*) FROM stock_consume_log WHERE stock_log_id = ?",
                Integer.class, stockLogId).intValue());
    }

    // 给真实消费服务装上 Spring 事务代理，允许各用例替换消费记录 Mapper 或库存 Mapper 来控制故障位置。
    private static StockConsumeService transactionalService(StockConsumeLogMapper receiptMapper, ItemStockDOMapper stockMapper) {
        StockConsumeService target = new StockConsumeService(stockLogMapper, receiptMapper, stockMapper);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (StockConsumeService) factory.getProxy();
    }

    // 包装真实 Mapper。仅在指定方法写入 1 行后注入故障，以验证已执行的 SQL 确实被回滚。
    private static <T> T failAfterWrite(Class<T> mapperType, T delegate, String writeMethod, RuntimeException fault) {
        return mapperType.cast(Proxy.newProxyInstance(mapperType.getClassLoader(), new Class<?>[]{mapperType},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(delegate, args);
                    } catch (InvocationTargetException error) {
                        // 还原 Mapper 抛出的异常，便于事务服务按实际错误处理。
                        throw error.getCause();
                    }
                    if (method.getName().equals(writeMethod)) {
                        assertEquals(1, result);
                        throw fault;
                    }
                    return result;
                }));
    }

    // 要求消费调用必须失败，并返回异常，让故障注入用例能核对异常确实来自注入点。
    private static RuntimeException expectFailure(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException failure) {
            return failure;
        }
        fail("Expected stock consumption to fail");
        return null;
    }

    // 给首批并发调用的集合点设置等待上限，避免有线程未到达 INSERT 时测试一直挂住。
    private static void await(CountDownLatch latch) {
        try {
            assertTrue("Competing transactions did not reach INSERT", latch.await(20, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for competing transactions", failure);
        }
    }

    // 只允许连接本机、使用专用名称的 MySQL 测试库。连接账号和密码从专用环境变量读取。
    private static String requireLocalTestDatabase(String jdbcUrl) {
        if (!jdbcUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("Expected a JDBC URL for the local stock-consumption test database");
        }
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        String host = uri.getHost();
        String path = uri.getPath();
        if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "[::1]".equals(host))
                || uri.getUserInfo() != null || path == null || !path.matches("/miaosha_stock_consume_it(?:_[a-z0-9]+)*")) {
            throw new IllegalArgumentException("Use a loopback MySQL host and a miaosha_stock_consume_it database");
        }
        return path.substring(1);
    }

    // 缺少测试库配置就立即报错，避免偷偷连到应用默认使用的数据库。
    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Set " + name + " before running StockConsumeMySqlIT");
        }
        return value;
    }
}
