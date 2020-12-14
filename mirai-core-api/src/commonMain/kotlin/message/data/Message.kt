/*
 * Copyright 2019-2020 Mamoe Technologies and contributors.
 *
 *  此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 *  Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 *  https://github.com/mamoe/mirai/blob/master/LICENSE
 */

@file:Suppress(
    "MemberVisibilityCanBePrivate", "unused", "EXPERIMENTAL_API_USAGE",
    "NOTHING_TO_INLINE", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE",
    "INAPPLICABLE_JVM_NAME"
)
@file:JvmMultifileClass
@file:JvmName("MessageUtils")

package net.mamoe.mirai.message.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.message.MessageReceipt
import net.mamoe.mirai.message.MessageSerializer
import net.mamoe.mirai.message.MessageSerializerImpl
import net.mamoe.mirai.utils.MiraiExperimentalApi
import net.mamoe.mirai.utils.PlannedRemoval
import net.mamoe.mirai.utils.safeCast
import kotlin.contracts.contract

/**
 * 可发送的或从服务器接收的消息.
 *
 * [消息][Message] 分为
 * - [SingleMessage]:
 *   - [MessageMetadata] 消息元数据, 即消息的属性. 包括: [消息来源][MessageSource], [引用回复][QuoteReply].
 *   - [MessageContent] 含内容的消息, 包括: [纯文本][PlainText], [@群员][At], [@全体成员][AtAll] 等.
 * - [MessageChain]: 不可变消息链, 链表形式链接的多个 [SingleMessage] 实例.
 *
 * #### 在 Kotlin 使用 [Message]:
 * 这与使用 [String] 的使用非常类似.
 *
 * - 比较 [SingleMessage] 与 [String]:
 *  `if(message.content == "你好") friend.sendMessage(event)`
 *
 * - 连接 [Message] 与 [Message], [String], (使用操作符 [Message.plus]):
 *  ```
 * val text = PlainText("Hello ") + PlainText("world") + "!"
 * friend.sendMessage(text) // "Hello world!"
 *  ```
 * 但注意: 不能 `String + Message`. 只能 `Message + String`
 *
 * #### 实现规范
 * 除 [MessageChain] 外, 所有 [Message] 的实现类都有伴生对象实现 [Key] 接口.
 *
 * #### 发送消息
 * - 通过 [Contact] 中的成员函数: [Contact.sendMessage]
 * - 通过 [Message] 的扩展函数: [Message.sendTo]
 * - 在 [MessageEvent] 中使用 [MessageEvent.reply] 等捷径
 *
 * @see PlainText 纯文本
 * @see Image 图片
 * @see Face 原生表情
 * @see At 一个群成员的引用
 * @see AtAll 全体成员的引用
 * @see QuoteReply 一条消息的引用
 * @see RichMessage 富文本消息, 如 [XML 和 JSON][ServiceMessage], [小程序][LightApp]
 * @see HummerMessage 一些特殊的消息, 如 [闪照][FlashImage], [戳一戳][PokeMessage], [VIP表情][VipFace]
 * @see CustomMessage 自定义消息类型
 *
 * @see MessageChain 消息链(即 `List<Message>`)
 * @see buildMessageChain 构造一个 [MessageChain]
 *
 * @see Contact.sendMessage 发送消息
 */
@Serializable(Message.Serializer::class)
public interface Message { // must be interface. Don't consider any changes.

    /**
     * 将 `this` 和 [tail] 连接.
     *
     * 连接后可以保证 [ConstrainSingle] 的元素单独存在.
     *
     * 例:
     * ```
     * val a = PlainText("Hello ")
     * val b = PlainText("world!")
     * val c: MessageChain = a + b
     * println(c) // "Hello world!"
     * ```
     *
     * 在 Java 使用 [plus]
     *
     * @see plus `+` 操作符重载
     */
    @JvmSynthetic // in java they should use `plus` instead
    public fun followedBy(tail: Message): MessageChain = followedByImpl(tail)

    /**
     * 得到包含 mirai 消息元素代码的, 易读的字符串. 如 `At(member) + "test"` 将转为 `"[mirai:at:qqId]test"`
     *
     * 在使用消息相关 DSL 和扩展时, 一些内容比较的实现均使用的是 [contentToString] 而不是 [toString]
     *
     * 各个 [SingleMessage] 的转换示例:
     * - [PlainText] : `"Hello"`
     * - [GroupImage] : `"[mirai:image:{01E9451B-70ED-EAE3-B37C-101F1EEBF5B5}.mirai]"`
     * - [FriendImage] : `"[mirai:image:/f8f1ab55-bf8e-4236-b55e-955848d7069f]"`
     * - [PokeMessage] : `"[mirai:poke:1,-1]"`
     * - [MessageChain] : 无间隔地连接所有元素 (`joinToString("")`)
     * - ...
     *
     * @see contentToString 转为最接近官方格式的字符串
     */
    public override fun toString(): String

    /**
     * 转为最接近官方格式的字符串. 如 `At(member) + "test"` 将转为 `"@群名片 test"`.
     *
     * 在使用消息相关 DSL 和扩展时, 一些内容比较的实现均使用 [contentToString] 而不是 [toString]
     *
     * 各个 [SingleMessage] 的转换示例:
     * - [PlainText] : `"Hello"`
     * - [Image] : `"[图片]"`
     * - [PokeMessage] : `"[戳一戳]"`
     * - [MessageChain] : 无间隔地连接所有元素 (`joinToString("", transformer=Message::contentToString)`)
     * - ...
     *
     * @see toString 得到包含 mirai 消息元素代码的, 易读的字符串
     */
    public fun contentToString(): String


    /**
     * 判断内容是否与 [another] 相等.
     *
     * 若本函数返回 `true`, 则表明:
     * - `this` 与 [another] 的 [contentToString] 相等
     */
    public fun contentEquals(another: Message, ignoreCase: Boolean = false): Boolean =
        this.contentToString().equals(another.contentToString(), ignoreCase = ignoreCase)
    // contentEqualsImpl(another, ignoreCase)

    /**
     * 判断内容是否与 [another] 相等.
     *
     * 若本函数返回 `true`, 则表明:
     * - `this` 与 [another] 的 [contentToString] 相等
     */
    public fun contentEquals(another: String, ignoreCase: Boolean = false): Boolean {
        return this.contentToString().equals(another, ignoreCase = ignoreCase)
    }

    /** 将 [another] 按顺序连接到这个消息的尾部. */
    public operator fun plus(another: MessageChain): MessageChain = this + another as Message

    /** 将 [another] 按顺序连接到这个消息的尾部. */
    public operator fun plus(another: Message): MessageChain = this.followedBy(another)

    /** 将 [another] 连接到这个消息的尾部. */
    public operator fun plus(another: SingleMessage): MessageChain = this.followedBy(another)

    /** 将 [another] 作为 [PlainText] 连接到这个消息的尾部. */
    public operator fun plus(another: String): MessageChain = this.followedBy(PlainText(another))

    /** 将 [another] 作为 [PlainText] 连接到这个消息的尾部. */
    public operator fun plus(another: CharSequence): MessageChain =
        this.followedBy(PlainText(another.toString()))

    /** 将 [another] 按顺序连接到这个消息的尾部. */
    public operator fun plus(another: Iterable<Message>): MessageChain =
        another.fold(this, Message::plus).asMessageChain()

    /** 将 [another] 按顺序连接到这个消息的尾部. */
    @JvmName("plusIterableString")
    public operator fun plus(another: Iterable<String>): MessageChain =
        another.fold(this, Message::plus).asMessageChain()

    /** 将 [another] 按顺序连接到这个消息的尾部. */
    public operator fun plus(another: Sequence<Message>): MessageChain =
        another.fold(this, Message::plus).asMessageChain()

    public object Serializer :
        MessageSerializer by MessageSerializerImpl,
        KSerializer<Message> by PolymorphicSerializer(Message::class)
}

/**
 * 标注一个实验性的 [MessageKey] API.
 *
 * 在 `2.0-M2` 时将会推进 [MessageKey] 到稳定状态并删除此注解.
 */
@PlannedRemoval("2.0-M2")
@MiraiExperimentalApi
@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
public annotation class ExperimentalMessageKey


@MiraiExperimentalApi
@JvmSynthetic
public suspend inline operator fun Message.plus(another: Flow<Message>): MessageChain =
    another.fold(this) { acc, it -> acc + it }.asMessageChain()


/**
 * [Message.contentToString] 的捷径
 */
@get:JvmSynthetic
public inline val Message.content: String
    get() = contentToString()


/**
 * 判断消息内容是否为空.
 *
 * 以下情况视为 "空消息":
 *
 * - 是 [MessageMetadata] (因为 [MessageMetadata.contentToString] 都必须返回空字符串)
 * - [PlainText] 长度为 0
 * - [MessageChain] 所有元素都满足 [isContentEmpty]
 */
public fun Message.isContentEmpty(): Boolean {
    return when (this) {
        is MessageMetadata -> true
        is PlainText -> this.content.isEmpty()
        is MessageChain -> this.all { it.isContentEmpty() }
        else -> false
    }
}

public inline fun Message.isContentNotEmpty(): Boolean = !this.isContentEmpty()

public inline fun Message.isPlain(): Boolean {
    contract {
        returns(true) implies (this@isPlain is PlainText)
        returns(false) implies (this@isPlain !is PlainText)
    }
    return this is PlainText
}

public inline fun Message.isNotPlain(): Boolean {
    contract {
        returns(false) implies (this@isNotPlain is PlainText)
        returns(true) implies (this@isNotPlain !is PlainText)
    }
    return this !is PlainText
}

/**
 * 将此消息元素按顺序重复 [count] 次.
 */
// inline: for future removal
public inline fun Message.repeat(count: Int): MessageChain {
    if (this is ConstrainSingle) {
        // fast-path
        return this.asMessageChain()
    }
    return buildMessageChain(count) {
        repeat(count) {
            add(this@repeat)
        }
    }
}

/**
 * 将此消息元素按顺序重复 [count] 次.
 */
@JvmSynthetic
public inline operator fun Message.times(count: Int): MessageChain = this.repeat(count)

/**
 * 单个消息元素. 与之相对的是 [MessageChain], 是多个 [SingleMessage] 的集合.
 */
@Serializable(SingleMessage.Serializer::class)
public interface SingleMessage : Message {
    public object Serializer : KSerializer<SingleMessage> by PolymorphicSerializer(SingleMessage::class)
}

/**
 * 消息元数据, 即不含内容的元素.
 *
 * 这种类型的 [Message] 只表示一条消息的属性. 其子类为 [MessageSource], [QuoteReply]
 *
 * 所有子类的 [contentToString] 都应该返回空字符串.
 *
 * @see MessageSource 消息源
 * @see QuoteReply 引用回复
 * @see CustomMessageMetadata 自定义元数据
 *
 * @see ConstrainSingle 约束一个 [MessageChain] 中只存在这一种类型的元素
 */
@Serializable(MessageMetadata.Serializer::class)
public interface MessageMetadata : SingleMessage {
    /**
     * 返回空字符串
     */
    override fun contentToString(): String = ""

    public object Serializer : KSerializer<MessageMetadata> by PolymorphicSerializer(MessageMetadata::class)
}

/**
 * 约束一个 [MessageChain] 中只存在这一种类型的元素. 新元素将会替换旧元素, 保持原顺序.
 *
 * 实现此接口的元素将会在连接时自动处理替换.
 *
 * @see AbstractMessageKey
 * @see AbstractPolymorphicMessageKey
 *
 * @see MessageSource
 */
@ExperimentalMessageKey
public interface ConstrainSingle : SingleMessage {
    /**
     * 用于判断是否为同一种元素的 [MessageKey]. 使用多态类型 [MessageKey] 最上层的 [MessageKey].
     * @see MessageKey 查看更多信息
     */
    @ExperimentalMessageKey
    public val key: MessageKey<*>
}

/**
 * 独立的 [MessageKey] 的实现. '独立' 即 `final`, 不支持多态类型. 适用于作为最顶层的 [MessageKey], 如 [MessageSource].
 *
 * @see AbstractPolymorphicMessageKey
 */
@ExperimentalMessageKey
public abstract class AbstractMessageKey<out M : SingleMessage>(
    override val safeCast: (SingleMessage) -> M?,
) : MessageKey<M>

/**
 * 多态 [MessageKey].
 *
 * 示例: [HummerMessage]
 * ```
 *               MessageContent
 *                     ↑
 *               HummerMessage
 *                     ↑
 *        +------------+-------------+
 *        |            |             |
 *  PokeMessage     VipFace      FlashImage
 *
 * ```
 *
 * 当 [连接][Message.plus] 一个 [VipFace] 到一个 [MessageChain] 时,
 * 由于 [VipFace] 最上层为 [MessageContent], 消息链中第一个 [MessageContent] 会被 (保留顺序地) 替换为 [VipFace], 其他所有 [MessageContent] 都会被删除.
 *
 * 如:
 * ```
 * val source: MessageSource = ...
 *
 * val result = messageChainOf(PlainText("a"), PlainText("b"), source, AtAll) + VipFace.LiuLian
 * // result 为 [VipFace.LiuLian, source]
 *
 * val result = source1 + source2
 * // result 为 [source2], 总是右侧替换左侧
 * ```
 */
@ExperimentalMessageKey
public abstract class AbstractPolymorphicMessageKey<out B : SingleMessage, out M : B>(
    baseKey: MessageKey<B>,
    safeCast: (SingleMessage) -> M?,
) : MessageKey<M>, AbstractMessageKey<M>(safeCast) {
    internal val topmostKey: MessageKey<*> =
        if (baseKey is AbstractPolymorphicMessageKey<*, *>) baseKey.topmostKey else baseKey
}

/**
 * 带内容的消息.
 *
 * @see PlainText 纯文本
 * @see At At 一个群成员.
 * @see AtAll At 全体成员
 * @see HummerMessage 一些特殊消息: [戳一戳][PokeMessage], [闪照][FlashImage]
 * @see Image 图片
 * @see RichMessage 富文本
 * @see ServiceMessage 服务消息, 如 JSON/XML
 * @see Face 原生表情
 * @see ForwardMessage 合并转发
 * @see Voice 语音
 */
@Serializable(MessageContent.Serializer::class)
public interface MessageContent : SingleMessage {
    @ExperimentalMessageKey
    public companion object Key : AbstractMessageKey<MessageContent>({ it.safeCast() })

    public object Serializer : KSerializer<MessageContent> by PolymorphicSerializer(MessageContent::class)
}

/**
 * 将 [this] 发送给指定联系人
 */
@JvmSynthetic
@Suppress("UNCHECKED_CAST")
public suspend inline fun <C : Contact> MessageChain.sendTo(contact: C): MessageReceipt<C> =
    contact.sendMessage(this) as MessageReceipt<C>

@JvmSynthetic
@Suppress("UNCHECKED_CAST")
public suspend inline fun <C : Contact> Message.sendTo(contact: C): MessageReceipt<C> =
    contact.sendMessage(this) as MessageReceipt<C>
