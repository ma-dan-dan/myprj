package com.hmdp.annotation;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
public class LogAspect {

    @Around("@annotation(Log)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature()).getMethod();
        Log log = method.getAnnotation(Log.class);

        System.out.println("前置增强：" + log.value());

        Object result;
        try {
            result = joinPoint.proceed();
            System.out.println("后置增强：方法执行成功");
        } catch (Throwable e) {
            System.out.println("异常增强：" + e.getMessage());
            throw e;
        }

        return result;
    }
}