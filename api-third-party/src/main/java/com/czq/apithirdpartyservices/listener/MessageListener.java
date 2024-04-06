package com.czq.apithirdpartyservices.listener;

import com.czq.apicommon.entity.SmsMessage;
import com.czq.apithirdpartyservices.utils.SendMessageOperation;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

import static com.czq.apicommon.constant.RabbitmqConstant.QUEUE_LOGIN_SMS;


/**
 * 短信发送监听器，监听到消息则发送短信验证码
 */
@Component
@Slf4j
public class MessageListener {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 监听queue_sms_code队列，实现接口统计功能
     * 生产者是懒加载机制，消费者是饿汉加载机制，二者机制不对应，所以消费者要自行创建队列并加载，否则会报错
     * @param smsMessage 接收到的消息体
     * @param message 消息元数据
     * @param channel 消息传输的通道
     * @throws IOException
     */
    @RabbitListener(queuesToDeclare = { @Queue(QUEUE_LOGIN_SMS)})
    public void receiveSms(SmsMessage smsMessage, Message message, Channel channel) throws IOException {
        log.info("监听到消息啦，内容是："+smsMessage);
        //确认收到消息，告知 RabbitMQ 可以将消息从队列中删除，避免消息重复消费
        channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);

        //新建SendMessageOperation对象（自定义对象），调用sendMessage方法发送邮箱验证码
        SendMessageOperation messageOperation = new SendMessageOperation();
        String targetEmail = smsMessage.getEmail();
        messageOperation.sendMessage(targetEmail,stringRedisTemplate);

    }

}