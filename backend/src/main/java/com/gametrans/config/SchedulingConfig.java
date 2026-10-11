package com.gametrans.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** presence 만료 같은 주기 작업을 켠다. */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
