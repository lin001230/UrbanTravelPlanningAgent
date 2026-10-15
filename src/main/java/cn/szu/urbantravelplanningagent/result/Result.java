package cn.szu.urbantravelplanningagent.result;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 统一接口响应。code 表示业务状态码
 *
 * @param <T> 响应数据类型
 */
@Data
@AllArgsConstructor
public class Result<T> {

    public static final int SUCCESS_CODE = 200;
    public static final int ERROR_CODE = 500;

    /** 业务状态码，200 表示成功。 */
    private Integer code;

    /** 返回给调用方的提示信息。 */
    private String message;

    /** 响应数据，无数据时为 null。 */
    private T data;

    public Result() {
        this(SUCCESS_CODE, "操作成功", null);
    }

    public static Result<Void> success() {
        return new Result<>();
    }

    public static <T> Result<T> success(T data) {
        return success("操作成功", data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(SUCCESS_CODE, message, data);
    }

    public static <T> Result<T> error(String message) {
        return error(ERROR_CODE, message);
    }

    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

}
