package inside.data.entity;

import discord4j.common.util.Snowflake;
import inside.data.entity.base.*;
import inside.event.audit.*;
import org.hibernate.annotations.Type;

import javax.persistence.*;
import java.io.Serial;
import java.util.*;

@Entity
@Table(name = "audit_action")
public class AuditAction extends GuildEntity{
    @Serial
    private static final long serialVersionUID = 165904719880729938L;

    @Column
    private Calendar timestamp;

    @Column
    @Enumerated(EnumType.STRING)
    private AuditEventType type;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "source_user_id", length = 21)),
            @AttributeOverride(name = "name", column = @Column(name = "source_user_name"))
    })
    private NamedReference user;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "target_user_id", length = 21)),
            @AttributeOverride(name = "name", column = @Column(name = "target_user_name"))
    })
    private NamedReference target;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "id", column = @Column(name = "channel_id", length = 21)),
            @AttributeOverride(name = "name", column = @Column(name = "channel_name"))
    })
    private NamedReference channel;

    @Type(type = "jsonb")
    @Column(columnDefinition = "json")
    private Map<String, Object> attributes;

    public AuditAction(){}

    public AuditAction(Snowflake guildId){
        this.guildId = Objects.requireNonNull(guildId, "guildId").asString();
    }

    public Calendar timestamp(){
        return timestamp;
    }

    public void timestamp(Calendar timestamp){
        this.timestamp = timestamp;
    }

    public AuditEventType type(){
        return type;
    }

    public void type(AuditEventType type){
        this.type = type;
    }

    public NamedReference user(){
        return user;
    }

    public void user(NamedReference user){
        this.user = user;
    }

    public NamedReference target(){
        return target;
    }

    public void target(NamedReference targetUser){
        this.target = targetUser;
    }

    public NamedReference channel(){
        return channel;
    }

    public void channel(NamedReference channel){
        this.channel = channel;
    }

    public Map<String, Object> attributes(){
        return attributes;
    }

    public void attributes(Map<String, Object> attributes){
        this.attributes = attributes;
    }

    @Transient
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key){
        if(attributes == null || attributes.isEmpty()){
            return null;
        }
        Object value = attributes.get(key);
        return value != null ? (T)value : null;
    }
}