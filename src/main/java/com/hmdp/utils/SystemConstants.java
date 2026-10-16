package com.hmdp.utils;

public class SystemConstants {
    public static final String IMAGE_UPLOAD_DIR = System.getenv().getOrDefault("IMAGE_UPLOAD_DIR", "./uploads");
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 8;
    public static final int MAX_PAGE_SIZE = 10;
}
