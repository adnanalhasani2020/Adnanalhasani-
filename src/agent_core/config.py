from dataclasses import dataclass
import os
@dataclass(frozen=True)
class Settings:
    environment:str="development"; log_level:str="INFO"
    @classmethod
    def from_environment(cls): return cls(os.getenv("APP_ENV","development"),os.getenv("APP_LOG_LEVEL","INFO"))
