package com.imooc.miaoshaproject.service.impl;

import com.imooc.miaoshaproject.dao.OrderDOMapper;
import com.imooc.miaoshaproject.dao.SequenceDOMapper;
import com.imooc.miaoshaproject.dataobject.SequenceDO;
import com.imooc.miaoshaproject.error.BusinessException;
import com.imooc.miaoshaproject.error.EmBusinessError;
import com.imooc.miaoshaproject.service.ItemService;
import com.imooc.miaoshaproject.service.OrderService;
import com.imooc.miaoshaproject.service.UserService;
import com.imooc.miaoshaproject.service.model.ItemModel;
import com.imooc.miaoshaproject.service.model.UserModel;
import org.junit.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class OrderTransactionTest {

    @Test
    public void checkedFailureAfterOrderWriteRollsBackAndCompensates() throws Exception {
        assertFailureRollsBack(new BusinessException(EmBusinessError.UNKNOWN_ERROR));
    }

    @Test
    public void runtimeFailureAfterOrderWriteRollsBackAndCompensates() throws Exception {
        assertFailureRollsBack(new IllegalStateException("injected sales failure"));
    }

    private void assertFailureRollsBack(Exception failure) throws Exception {
        List<String> events = new ArrayList<>();
        ItemModel item = new ItemModel();
        item.setId(7);
        item.setPrice(BigDecimal.TEN);
        UserModel user = new UserModel();
        user.setId(23);
        SequenceDO sequence = new SequenceDO();
        sequence.setName("order_info");
        sequence.setCurrentValue(1);
        sequence.setStep(1);

        OrderServiceImpl target = new OrderServiceImpl();
        setField(target, "itemService", stub(ItemService.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getItemByIdInCache":
                    return item;
                case "decreaseStock":
                    return true;
                case "increaseSales":
                    throw failure;
                case "increaseStock":
                    events.add("restore");
                    return true;
                default:
                    throw new AssertionError("Unexpected item call: " + method.getName());
            }
        }));
        setField(target, "userService", stub(UserService.class, (proxy, method, args) -> {
            if (method.getName().equals("getUserByIdInCache")) {
                return user;
            }
            throw new AssertionError("Unexpected user call: " + method.getName());
        }));
        setField(target, "sequenceDOMapper", stub(SequenceDOMapper.class, (proxy, method, args) -> {
            if (method.getName().equals("getSequenceByName")) {
                return sequence;
            }
            if (method.getName().equals("updateByPrimaryKeySelective")) {
                return 1;
            }
            throw new AssertionError("Unexpected sequence call: " + method.getName());
        }));
        setField(target, "orderDOMapper", stub(OrderDOMapper.class, (proxy, method, args) -> {
            if (method.getName().equals("insertSelective")) {
                events.add("insert");
                return 1;
            }
            throw new AssertionError("Unexpected order call: " + method.getName());
        }));

        // Use real Spring advice; record transaction decisions without external services.
        AbstractPlatformTransactionManager manager = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                events.add("begin");
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                events.add("commit");
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                events.add("rollback");
            }
        };
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        OrderService service = (OrderService) factory.getProxy();

        try {
            service.createOrder(23, 7, null, 1, "test-stock-log");
            fail("Expected the injected failure");
        } catch (Exception actual) {
            assertSame(failure, actual);
        }
        assertEquals(Arrays.asList("begin", "insert", "rollback", "restore"), events);
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = OrderServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
