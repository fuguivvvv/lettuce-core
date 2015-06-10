// Copyright (C) 2011 - Will Glozer.  All rights reserved.

package com.lambdaworks.redis;

/**
 * Exception thrown when the thread executing a redis command is interrupted.
 * 
 * @author Will Glozer
 */
@SuppressWarnings("serial")
public class RedisCommandInterruptedException extends RedisException {

    public RedisCommandInterruptedException(String msg) {
        super(msg);
    }

    public RedisCommandInterruptedException(String msg, Throwable e) {
        super(msg, e);
    }

    public RedisCommandInterruptedException(Throwable e) {
        super("Command interrupted", e);
    }
}
