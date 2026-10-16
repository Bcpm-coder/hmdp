package com.hmdp.utils;

public interface ILock {
    boolean tryLock(long time);
    void unlock();
}
