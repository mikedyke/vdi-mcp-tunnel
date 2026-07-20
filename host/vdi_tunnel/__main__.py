from .config import Config
from .proxy import Proxy

if __name__ == "__main__":
    Proxy(Config()).serve()
