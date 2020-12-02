package insidebot.common.services.impl;

import arc.util.Log;
import discord4j.common.util.Snowflake;
import discord4j.core.*;
import discord4j.core.event.ReactiveEventAdapter;
import discord4j.core.object.entity.channel.*;
import discord4j.core.shard.MemberRequestFilter;
import discord4j.gateway.intent.*;
import insidebot.Settings;
import insidebot.common.services.DiscordService;
import insidebot.data.service.GuildService;
import insidebot.event.dispatcher.EventListener;
import insidebot.event.dispatcher.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;
import reactor.util.annotation.NonNull;

import javax.annotation.PreDestroy;
import java.util.*;

@Service
public class DiscordServiceImpl implements DiscordService{
    @Autowired
    private Settings settings;

    @Autowired
    private GuildService guildService;

    @Autowired
    private List<Events> events;

    protected GatewayDiscordClient gateway;
    protected EventListener eventListener;

    @Autowired(required = false)
    public void init(List<ReactiveEventAdapter> handlers){
        String token = settings.token;
        Objects.requireNonNull(token, "Discord token not provided");

        gateway = DiscordClient.create(token)
                               .gateway()
                               .setMemberRequestFilter(MemberRequestFilter.all())
                               .setEnabledIntents(IntentSet.of(
                                       Intent.GUILD_MEMBERS,
                                       Intent.GUILDS,
                                       Intent.GUILD_MESSAGES,
                                       Intent.GUILD_MESSAGE_REACTIONS,
                                       Intent.DIRECT_MESSAGES,
                                       Intent.DIRECT_MESSAGE_REACTIONS
                               ))
                               .login()
                               .block();

        eventListener = EventListener.buffering();

        Flux.fromIterable(events)
            .filter(Objects::nonNull)
            .subscribe(e -> eventListener.on(e).subscribe(), Log::err);

        Flux.fromIterable(handlers)
            .filter(Objects::nonNull)
            .subscribe(e -> gateway.on(e).subscribe(), Log::err);
    }

    @PreDestroy
    public void destroy(){
        gateway.logout().block();
    }

    @Override
    public GatewayDiscordClient gateway(){
        return gateway;
    }

    @Override
    public EventListener eventListener(){
        return eventListener;
    }

    @Override
    public Mono<TextChannel> getTextChannelById(@NonNull Snowflake channelId){
        return gateway.getChannelById(channelId).cast(TextChannel.class);
    }

    @Override
    public Mono<TextChannel> getLogChannel(Snowflake guildId){
        return getTextChannelById(guildService.logChannelId(guildId));
    }

    @Override
    public Mono<VoiceChannel> getVoiceChannelById(@NonNull Snowflake channelId){
        return gateway.getChannelById(channelId).cast(VoiceChannel.class);
    }

    @Override
    public boolean exists(Snowflake guildId, Snowflake userId){
        try{
            gateway.getMemberById(guildId, userId).block();
            return true;
        }catch(Throwable t){
            return true;
        }
    }
}
