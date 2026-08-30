package com.pathfinder.service;

/**
 * 业务异常：携带 HTTP 语义与业务提示。
 */
public class BizException extends RuntimeException {

    private final int status;

    public BizException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static BizException badRequest(String msg) {
        return new BizException(400, msg);
    }

    public static BizException forbidden(String msg) {
        return new BizException(403, msg);
    }

    public static BizException notFound(String msg) {
        return new BizException(404, msg);
    }

    public static BizException unauthorized(String msg) {
        return new BizException(401, msg);
    }
}
