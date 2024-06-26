package top.ochiamalu.utils;

import java.util.Random;

import static top.ochiamalu.constants.SystemConstants.MAXIMUM_VERIFICATION_CODE_NUM;
import static top.ochiamalu.constants.SystemConstants.MINIMUM_VERIFICATION_CODE_NUM;

/**
 * 验证码生成工具
 *
 * @author OchiaMalu
 * @date 2023/06/22
 */
public final class ValidateCodeUtils {
    private ValidateCodeUtils() {
    }

    /**
     * 生成验证代码
     *
     * @return {@link Integer}
     */
    public static Integer generateValidateCode() {
        //生成随机数，最大为999999
        int code = new Random().nextInt(MAXIMUM_VERIFICATION_CODE_NUM);
        if (code < MINIMUM_VERIFICATION_CODE_NUM) {
            //保证随机数为6位数字
            code = code + MINIMUM_VERIFICATION_CODE_NUM;
        }
        return code;
    }
}
