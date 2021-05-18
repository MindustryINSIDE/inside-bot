package inside.event;

import discord4j.common.util.Snowflake;
import discord4j.core.event.ReactiveEventAdapter;
import discord4j.core.event.domain.message.*;
import discord4j.core.object.Embed.Field;
import discord4j.core.object.audit.*;
import discord4j.core.object.entity.*;
import discord4j.core.object.entity.channel.TextChannel;
import inside.audit.*;
import inside.command.CommandHandler;
import inside.command.model.CommandEnvironment;
import inside.data.entity.*;
import inside.data.service.EntityRetriever;
import inside.service.MessageService;
import inside.util.*;
import inside.util.io.ReusableByteInputStream;
import org.joda.time.DateTime;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.*;
import reactor.util.context.Context;
import reactor.util.function.Tuples;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;

import static inside.audit.Attribute.*;
import static inside.audit.AuditActionType.*;
import static inside.audit.BaseAuditProvider.MESSAGE_TXT;
import static inside.event.MemberEventHandler.TIMEOUT_MILLIS;
import static inside.util.ContextUtil.*;
import static reactor.function.TupleUtils.*;

@Component
public class MessageEventHandler extends ReactiveEventAdapter{
    @Autowired
    private CommandHandler commandHandler;

    @Autowired
    private EntityRetriever entityRetriever;

    @Autowired
    private MessageService messageService;

    @Autowired
    private AuditService auditService;

    @Override
    public Publisher<?> onMessageCreate(MessageCreateEvent event){
        Message message = event.getMessage();
        Member member = event.getMember().orElse(null);
        if(DiscordUtil.isBot(member) || MessageUtil.isEmpty(message) || message.getType() != Message.Type.DEFAULT || message.isTts() || !message.getEmbeds().isEmpty()){
            return Mono.empty();
        }

        Snowflake guildId = member.getGuildId();

        Mono<LocalMember> localMember = entityRetriever.getLocalMemberById(member)
                .switchIfEmpty(entityRetriever.createLocalMember(member));

        DateTime time = new DateTime(message.getTimestamp().toEpochMilli());

        Mono<Void> updateLastSendMessage = localMember.flatMap(localMember0 -> {
            localMember0.lastSentMessage(time);
            return entityRetriever.save(localMember0);
        });

        Mono<Void> safeMessageInfo = entityRetriever.getAuditConfigById(guildId).flatMap(auditConfig -> {
            if(auditConfig.isEnabled(MESSAGE_CREATE)){
                return entityRetriever.createMessageInfo(message).then();
            }
            return Mono.empty();
        });

        Mono<Context> initContext = entityRetriever.getGuildConfigById(guildId)
                .switchIfEmpty(entityRetriever.createGuildConfig(guildId))
                .map(guildConfig -> Context.of(KEY_LOCALE, guildConfig.locale(),
                        KEY_TIMEZONE, guildConfig.timeZone()));

        Mono<Void> handleMessage = localMember.zipWith(initContext)
                .map(function((localMember0, context) -> CommandEnvironment.builder()
                        .message(message)
                        .member(member)
                        .context(context)
                        .localMember(localMember0)
                        .build()))
                .flatMap(environment -> commandHandler.handleMessage(environment));

        // TODO: cleanup

        return initContext.flatMap(context -> Mono.when(updateLastSendMessage, safeMessageInfo, handleMessage).contextWrite(context));
    }

    @Override
    public Publisher<?> onMessageUpdate(MessageUpdateEvent event){
        Snowflake guildId = event.getGuildId().orElse(null);
        if(guildId == null || !event.isContentChanged()){
            return Mono.empty();
        }

        Mono<Context> initContext = entityRetriever.getGuildConfigById(guildId)
                .switchIfEmpty(entityRetriever.createGuildConfig(guildId))
                .map(guildConfig -> Context.of(KEY_LOCALE, guildConfig.locale(),
                        KEY_TIMEZONE, guildConfig.timeZone()));

        return initContext.flatMap(context -> Mono.zip(event.getMessage(), event.getChannel().ofType(TextChannel.class))
                .filter(predicate((message, channel) -> !message.isTts()))
                .zipWhen(tuple -> tuple.getT1().getAuthorAsMember(),
                        (tuple, user) -> Tuples.of(tuple.getT1(), tuple.getT2(), user))
                .filter(predicate((message, channel, member) -> DiscordUtil.isNotBot(member)))
                .flatMap(function((message, channel, member) -> {
                    String newContent = MessageUtil.effectiveContent(message);
                    Mono<MessageInfo> messageInfo = entityRetriever.getMessageInfoById(event.getMessageId())
                            .switchIfEmpty(Mono.fromSupplier(() -> {
                                MessageInfo info = new MessageInfo();
                                info.messageId(event.getMessageId());
                                info.userId(member.getId());
                                info.guildId(guildId);
                                info.content(messageService.encrypt(event.getOld()
                                        .map(MessageUtil::effectiveContent)
                                        .orElse(""), message.getId(), message.getChannelId()));
                                info.timestamp(new DateTime(event.getMessageId().getTimestamp().toEpochMilli()));
                                return info;
                            }));

                    Mono<?> command = Mono.defer(() -> {
                        if(messageService.isAwaitEdit(message.getId())){
                            return entityRetriever.getLocalMemberById(member)
                                    .switchIfEmpty(entityRetriever.createLocalMember(member))
                                    .flatMap(localMember -> commandHandler.handleMessage(CommandEnvironment.builder()
                                            .localMember(localMember)
                                            .message(message)
                                            .member(member)
                                            .context(context)
                                            .build()));
                        }
                        return Mono.empty();
                    });

                    return messageInfo.flatMap(info -> {
                        String oldContent = messageService.decrypt(info.content(), message.getId(), message.getChannelId());
                        info.content(messageService.encrypt(newContent, message.getId(), message.getChannelId()));

                        if(newContent.equals(oldContent)){ // message was pinned
                            return Mono.empty();
                        }

                        AuditActionBuilder builder = auditService.log(guildId, MESSAGE_EDIT)
                                .withChannel(channel)
                                .withUser(member)
                                .withAttribute(OLD_CONTENT, oldContent)
                                .withAttribute(NEW_CONTENT, newContent)
                                .withAttribute(AVATAR_URL, member.getAvatarUrl())
                                .withAttribute(MESSAGE_ID, message.getId());

                        if(newContent.length() >= Field.MAX_VALUE_LENGTH || oldContent.length() >= Field.MAX_VALUE_LENGTH){
                            builder.withAttachment(MESSAGE_TXT, ReusableByteInputStream.ofString(String.format("%s%n%s%n%n%s%n%s",
                                    messageService.get(context, "audit.message.old-content.title"), oldContent,
                                    messageService.get(context, "audit.message.new-content.title"), newContent
                            )));
                        }

                        return builder.save().and(entityRetriever.save(info));
                    }).and(command);
                }))
                .contextWrite(context));
    }

    @Override
    public Publisher<?> onMessageDelete(MessageDeleteEvent event){
        Message message = event.getMessage().orElse(null);
        if(message == null || !message.getEmbeds().isEmpty()){
            return Mono.empty();
        }

        User author = message.getAuthor().orElse(null);
        Snowflake guildId = event.getGuildId().orElse(null);
        if(DiscordUtil.isBot(author) || guildId == null){
            return Mono.empty();
        }

        Mono<MessageInfo> messageInfo = entityRetriever.getMessageInfoById(message.getId());

        Mono<Context> initContext = entityRetriever.getGuildConfigById(guildId)
                .switchIfEmpty(entityRetriever.createGuildConfig(guildId))
                .map(guildConfig -> Context.of(KEY_LOCALE, guildConfig.locale(),
                        KEY_TIMEZONE, guildConfig.timeZone()));

        return initContext.flatMap(context -> Mono.zip(event.getChannel().ofType(TextChannel.class), messageInfo)
                .flatMap(function((channel, info) -> {
                    String decrypted = messageService.decrypt(info.content(), message.getId(), message.getChannelId());
                    AuditActionBuilder builder = auditService.log(guildId, MESSAGE_DELETE)
                            .withChannel(channel)
                            .withAttribute(OLD_CONTENT, decrypted);

                    if(decrypted.length() >= Field.MAX_VALUE_LENGTH){
                        builder.withAttachment(MESSAGE_TXT, ReusableByteInputStream.ofString(String.format("%s%n%s",
                                messageService.get(context, "audit.message.deleted-content.title"), decrypted
                        )));
                    }

                    Mono<User> responsibleUser = event.getGuild()
                            .flatMapMany(guild -> guild.getAuditLog(spec -> spec.setActionType(ActionType.MESSAGE_DELETE)))
                            .flatMap(part -> Flux.fromIterable(part.getEntries()))
                            .sort(Comparator.comparing(AuditLogEntry::getId).reversed())
                            .filter(entry -> entry.getId().getTimestamp().isAfter(Instant.now(Clock.systemUTC()).minusMillis(TIMEOUT_MILLIS)) ||
                                    entry.getOption(OptionKey.COUNT).map(i -> i > 1).orElse(false) &&
                                    entry.getId().getTimestamp().isAfter(Instant.now(Clock.systemUTC()).minus(5, ChronoUnit.MINUTES)))
                            .filter(entry -> entry.getTargetId().map(id -> id.equals(info.userId())).orElse(false) &&
                                    entry.getOption(OptionKey.CHANNEL_ID).map(id -> id.equals(message.getChannelId())).orElse(false))
                            .next()
                            .flatMap(entry -> Mono.justOrEmpty(entry.getResponsibleUser()));

                    return responsibleUser.defaultIfEmpty(author).map(user -> builder.withUser(user)
                            .withTargetUser(author)
                            .withAttribute(AVATAR_URL, author.getAvatarUrl()))
                            .flatMap(AuditActionBuilder::save)
                            .and(entityRetriever.delete(info));
                }))
                .contextWrite(context));
    }
}
