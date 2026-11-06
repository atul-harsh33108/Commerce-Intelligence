from langchain.chat_models import init_chat_model

from app.conf.app_config import app_config

_kwargs = dict(
    model=app_config.llm.model_name,
    model_provider=app_config.llm.provider,
    api_key=app_config.llm.api_key,
    temperature=0,
)
if app_config.llm.base_url:
    _kwargs["base_url"] = app_config.llm.base_url

llm = init_chat_model(**_kwargs)


if __name__ == '__main__':
    for chunk in llm.stream("What is the meaning of life?"):
        print(chunk.text)
