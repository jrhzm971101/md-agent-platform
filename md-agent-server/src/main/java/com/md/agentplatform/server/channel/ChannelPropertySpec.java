package com.md.agentplatform.server.channel;

/**
 * 渠道类型的一个连接凭据字段 —— 页面据此动态渲染输入框。
 *
 * <p>它是「类型」的知识，不是「渠道」的数据：一个渠道行里存的 {@code properties}
 * 是 key→value，而哪些 key 该出现、哪个是必填、哪个要打码，来自这里。
 * 这样加一个平台只需要加一个工厂（连同它的描述），页面不需要为每个平台写一份表单。
 *
 * @param key         存进 {@code md_channel.properties} 的 key，与官方 {@code XxxChannelProperties.from()}
 *                    读取的 key 必须逐字一致（写错的表现是"装配时说必填项缺失"，而页面上明明填了）
 * @param label       页面上的中文标签
 * @param required    是否必填；装配前的校验按它判
 * @param secret      是否敏感（页面用密码框，且列表接口里打码）
 * @param placeholder 输入提示（例：cli_xxxxxxxxxxxxxxxx）
 * @param hint        补充说明；没有就传 null
 */
public record ChannelPropertySpec(
        String key, String label, boolean required, boolean secret, String placeholder, String hint) {

    /** 必填、非敏感字段的简写。 */
    public static ChannelPropertySpec required(String key, String label, String placeholder) {
        return new ChannelPropertySpec(key, label, true, false, placeholder, null);
    }

    /** 必填、非敏感、且需要一句补充说明的字段。 */
    public static ChannelPropertySpec required(String key, String label, String placeholder, String hint) {
        return new ChannelPropertySpec(key, label, true, false, placeholder, hint);
    }

    /** 必填、敏感字段（密钥/token）的简写。 */
    public static ChannelPropertySpec secret(String key, String label, String hint) {
        return new ChannelPropertySpec(key, label, true, true, null, hint);
    }

    /** 选填字段的简写。 */
    public static ChannelPropertySpec optional(String key, String label, String placeholder, String hint) {
        return new ChannelPropertySpec(key, label, false, false, placeholder, hint);
    }
}
