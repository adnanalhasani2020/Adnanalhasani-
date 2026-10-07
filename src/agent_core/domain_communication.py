from dataclasses import dataclass,field
from enum import Enum
from uuid import UUID
from agent_core.shared import ValidationError,new_id
class ConversationState(str,Enum): OPEN="open"; CLOSED="closed"; ARCHIVED="archived"
class MessageState(str,Enum): DRAFT="draft"; SENT="sent"; REVOKED="revoked"
@dataclass
class Conversation:
    id:UUID=field(default_factory=new_id); state:ConversationState=ConversationState.OPEN
    def close(self): self.state=ConversationState.CLOSED
    def archive(self): self.state=ConversationState.ARCHIVED
@dataclass(frozen=True)
class ChannelContext:
    channel_type:str
    def __post_init__(self):
        if not isinstance(self.channel_type,str) or not self.channel_type.strip(): raise ValidationError("ChannelContext.channel_type is required")
@dataclass
class Message:
    conversation_id:UUID; content:str; id:UUID=field(default_factory=new_id); state:MessageState=MessageState.DRAFT
    def __post_init__(self):
        if not isinstance(self.conversation_id,UUID): raise ValidationError("Message must reference Conversation")
        if not isinstance(self.content,str) or not self.content.strip(): raise ValidationError("Message.content is required")
        self.content=self.content.strip()
    def send(self): self.state=MessageState.SENT
    def revoke(self): self.state=MessageState.REVOKED
