package com.imooc.miaoshaproject.controller;

import com.google.common.util.concurrent.RateLimiter;
import com.imooc.miaoshaproject.error.BusinessException;
import com.imooc.miaoshaproject.error.EmBusinessError;
import com.imooc.miaoshaproject.mq.MqProducer;
import com.imooc.miaoshaproject.response.CommonReturnType;
import com.imooc.miaoshaproject.service.ItemService;
import com.imooc.miaoshaproject.service.OrderService;
import com.imooc.miaoshaproject.service.PromoService;
import com.imooc.miaoshaproject.service.model.OrderModel;
import com.imooc.miaoshaproject.service.model.UserModel;
import com.imooc.miaoshaproject.util.CodeUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Created by hzllb on 2018/11/18.
 */
@Controller("order")
@RequestMapping("/order")
@CrossOrigin(origins = {"*"},allowCredentials = "true")
public class OrderController extends BaseController {
    @Autowired
    private OrderService orderService;

    @Autowired
    private HttpServletRequest httpServletRequest;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private MqProducer mqProducer;

    @Autowired
    private ItemService itemService;

    @Autowired
    private PromoService promoService;

    private ExecutorService executorService;

    private RateLimiter orderCreateRateLimiter;

    @Value("${miaosha.sold-out-flag:true}")
    private boolean soldOutFlag;

    @Value("${miaosha.second-kill-gate:true}")
    private boolean secondKillGate;

    @Value("${miaosha.queue-discharge:true}")
    private boolean queueDischarge;

    @Value("${miaosha.verify-code:true}")
    private boolean verifyCodeEnabled;

    @PostConstruct
    public void init(){
        executorService = Executors.newFixedThreadPool(20);

        // 有界队列改造，newFixedThreadPool是无界队列
        executorService = new ThreadPoolExecutor(
                20, 20,                             // 核心线程 = 最大线程 = 20
                0L, TimeUnit.MILLISECONDS,           // 空闲线程存活时间（不适用，因为 core=max）
                new LinkedBlockingQueue<>(100),      // 有界队列，容量 100
                new ThreadPoolExecutor.AbortPolicy() // 队列满时抛异常，不静默丢弃
        );

        orderCreateRateLimiter = RateLimiter.create(5);         // 新增：每秒 300 个许可
    }
    // 331

    @RequestMapping(value = "/generateverifycode", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public void generateverifycode(HttpServletResponse response) throws BusinessException, IOException {
        // 校验用户登录状态
        String token = httpServletRequest.getParameterMap().get("token")[0];
        if (StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登陆，不能生成验证码");
        }
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
        if (userModel == null) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登陆，不能生成验证码");
        }

        // 调用工具类生成验证码
        Map<String, Object> map = CodeUtil.generateCodeAndPic();

        // 将验证码存入 Redis，key = verify_code_{userId}，10 分钟有效期
        redisTemplate.opsForValue().set("verify_code_" + userModel.getId(), map.get("code"));
        redisTemplate.expire("verify_code_" + userModel.getId(), 30, TimeUnit.MINUTES);

        // 将验证码图片输出到 response
        ImageIO.write((RenderedImage) map.get("codePic"), "jpeg", response.getOutputStream());
    }

    @RequestMapping(value = "/generatetoken", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType generatetoken(@RequestParam(name = "itemId") Integer itemId,
                                          @RequestParam(name = "promoId") Integer promoId,
                                          @RequestParam(name = "verifyCode") String verifyCode) throws BusinessException {

        // 获取用户登录 token
        String token = httpServletRequest.getParameterMap().get("token")[0];
        if (StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登陆，不能下单");
        }
        // 从 Redis 获取用户信息
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
        if (userModel == null) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登陆，不能下单");
        }

        // 验证码校验（可通过 miaosha.verify-code=false 关闭，方便压测）
        if (verifyCodeEnabled) {
            String redisVerifyCode = (String) redisTemplate.opsForValue().get("verify_code_" + userModel.getId());
            if (StringUtils.isEmpty(redisVerifyCode)) {
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "请求非法");
            }
            if (!redisVerifyCode.equalsIgnoreCase(verifyCode)) {
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "请求非法，验证码错误");
            }
        }

        // 调用秒杀令牌生成
        String promoToken = promoService.generateSecondKillToken(promoId, itemId, userModel.getId());
        if (promoToken == null) {
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "生成令牌失败");
        }
        return CommonReturnType.create(promoToken);
    }


    //封装下单请求
    @RequestMapping(value = "/createorder",method = {RequestMethod.POST},consumes={CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType createOrder(@RequestParam(name="itemId")Integer itemId,
                                        @RequestParam(name="amount")Integer amount,
                                        @RequestParam(name="promoId",required = false)Integer promoId,
                                        @RequestParam(name="promoToken",required = false)String promoToken) throws BusinessException {

        // 限流判断
        if (!orderCreateRateLimiter.tryAcquire()) {
            throw new BusinessException(EmBusinessError.RATELIMIT);
        }



        //Boolean isLogin = (Boolean) httpServletRequest.getSession().getAttribute("IS_LOGIN");
        String token = httpServletRequest.getParameterMap().get("token")[0];
        if(StringUtils.isBlank(token)){
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能下单");
        }

        //获取用户的登陆信息
        //UserModel userModel = (UserModel)httpServletRequest.getSession().getAttribute("LOGIN_USER");

        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
        if(userModel==null){
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能下单");
        }

        // 秒杀大闸：校验令牌（可通过 miaosha.second-kill-gate=false 关闭，方便压测）
        if (secondKillGate && promoId != null) {
            String inRedisPromoToken = (String) redisTemplate.opsForValue().get(
                    "promo_token_" + promoId + "_userid_" + userModel.getId() + "_itemid_" + itemId);
            if (inRedisPromoToken == null) {
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "秒杀令牌校验失败");
            }
            if (!org.apache.commons.lang3.StringUtils.equals(promoToken, inRedisPromoToken)) {
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "秒杀令牌校验失败");
            }
        }



        //OrderModel orderModel = orderService.createOrder(userModel.getId(),itemId,promoId,amount);

        // 售罄快速失败（可通过 miaosha.sold-out-flag=false 关闭，方便压测）
        if (soldOutFlag && redisTemplate.hasKey("promo_item_stock_invalid_" + itemId)) {
            throw new BusinessException(EmBusinessError.STOCK_NOT_ENOUGH);
        }

        //// 加入库存流水 init 状态
        //String stockLogId = itemService.initStockLog(itemId, amount);
        //
        //// 再去完成对应的下单事务型消息机制
        //if (!mqProducer.transactionAsyncReduceStock(userModel.getId(), itemId, promoId, amount, stockLogId)) {
        //    throw new BusinessException(EmBusinessError.UNKNOWN_ERROR, "下单失败");
        //}


        // 队列泄洪（可通过 miaosha.queue-discharge=false 关闭，变为同步直调，方便压测对比）
        if (queueDischarge) {
            // 同步调用线程池的 submit 方法
            // 拥塞窗口为 20 的等待队列，用来队列化泄洪
            try {
                Future<Object> future = executorService.submit(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        // 加入库存流水 init 状态
                        String stockLogId = itemService.initStockLog(itemId, amount);
                        // 再去完成对应的下单事务型消息机制
                        if (!mqProducer.transactionAsyncReduceStock(userModel.getId(), itemId, promoId, amount, stockLogId)) {
                            throw new BusinessException(EmBusinessError.UNKNOWN_ERROR, "下单失败");
                        }
                        return null;
                    }
                });
                future.get();     // 同步阻塞等待结果
            } catch (RejectedExecutionException e) {
                // 线程池队列满了，拒绝新任务
                throw new BusinessException(EmBusinessError.RATELIMIT);
            } catch (InterruptedException e) {
                throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
            } catch (ExecutionException e) {
                throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
            }
        } else {
            // 同步直调模式（不经过线程池），方便压测对比延迟和吞吐
            String stockLogId = itemService.initStockLog(itemId, amount);
            if (!mqProducer.transactionAsyncReduceStock(userModel.getId(), itemId, promoId, amount, stockLogId)) {
                throw new BusinessException(EmBusinessError.UNKNOWN_ERROR, "下单失败");
            }
        }

        return CommonReturnType.create(null);
    }
}
