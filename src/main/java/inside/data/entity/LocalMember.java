package inside.data.entity;

import discord4j.common.util.Snowflake;
import inside.data.entity.base.GuildEntity;
import reactor.util.annotation.Nullable;

import javax.persistence.*;
import java.io.Serial;
import java.util.*;

@Entity
@Table(name = "local_member")
public class LocalMember extends GuildEntity{
    @Serial
    private static final long serialVersionUID = -9169934990408633927L;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "effective_name", length = 32)
    private String effectiveName;

    @Column(name = "last_sent_message")
    private Calendar lastSentMessage;

    public Snowflake userId(){
        return Snowflake.of(userId);
    }

    public void userId(Snowflake userId){
        this.userId = Objects.requireNonNull(userId, "userId").asString();
    }

    public String effectiveName(){
        return effectiveName;
    }

    public void effectiveName(String effectiveName){
        this.effectiveName = Objects.requireNonNull(effectiveName, "effectiveName");
    }

    @Nullable
    public Calendar lastSentMessage(){
        return lastSentMessage;
    }

    public void lastSentMessage(@Nullable Calendar lastSentMessage){
        this.lastSentMessage = lastSentMessage;
    }

    @Override
    public String toString(){
        return "LocalMember{" +
               "userId='" + userId + '\'' +
               ", effectiveName='" + effectiveName + '\'' +
               ", lastSentMessage=" + lastSentMessage +
               "} " + super.toString();
    }
}
