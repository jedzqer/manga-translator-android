# API获取指南

> 本指南面向软件最新版本；旧版本的设置界面可能有所不同。服务商的地址和模型列表可能变化，请以服务商控制台为准。

本软件需要 **自备 API** 才能进行漫画翻译。

如果你还不了解什么是 API，可以先在网上搜索相关资料，或者**询问 AI**，获取一个可用的 API 服务。如果有条件的话，建议使用 grok，其对 nsfw 的容忍度更高。

## 填写前先准备什么

在软件设置页里，你通常需要准备这几项内容：

1. API 格式
2. API 地址
3. API Key
4. 模型名称

一般来说：

* API 格式需与服务商接口一致，可选「OpenAI 兼容」「OpenAI Responses」或「Gemini」
* API 地址通常是服务商提供的接口地址；兼容 OpenAI 的服务填写服务商给出的上级接口地址即可，例如 `https://api.deepseek.com/v1`、`https://open.bigmodel.cn/api/paas/v4`
* 软件会为 OpenAI 兼容接口补全 `/chat/completions`，为 OpenAI Responses 补全 `/responses`；若地址已以对应端点结尾则原样使用
* 点击「获取模型列表」时，两种 OpenAI 格式会使用 `/models` 端点；Gemini 使用其对应的模型列表接口
* API Key 就是服务商后台生成的密钥
* 模型名称就是你实际要调用的模型 ID

如果你不知道该填什么，最简单的方式就是去对应平台的 API 文档或控制台查看。

## 供应商选择建议

- 大厂直供：阿里云百炼、DeepSeek等，通常稳定性和速度较好。
- 第三方平台：例如硅基流动，可能会通过福利吸引用户。
- 各种自营中转站：来路不明，可能存在模型参水、隐私泄露等严重问题，但拥有极致的性价比。


## 硅基流动 - 第三方平台

你也可以通过这个邀请码注册硅基流动账号，实名后有16元赠金：

https://cloud.siliconflow.cn/i/bfTLExxn （此为作者邀请码，可以给作者回血，用上更好的AI开发软件😘）

大致流程如下：

1. 注册硅基流动账号
2. 在网站里完成实名认证
3. 申请一个 API Key
4. 把 API Key 复制到软件里
5. 点击软件里的 **获取模型列表** 按钮
6. 选择一个喜欢的模型；如果不知道选哪个，可以先用默认的

补充说明：

* 如果想体验免费额度，记得先完成实名认证
* 如果看不到实名认证页面，可以在浏览器里切换到电脑模式，再访问硅基流动电脑版页面
* 硅基流动整体速度可能会比较慢

## 小提示

* 不要把自己的 API Key 发给别人
* 如果获取模型列表失败，先检查 API 地址和 Key 有没有填错
* 某些平台可能会有余额、实名认证、地区或频率限制，遇到报错时优先去平台后台查看提示，看看是不是没有实名认证，或者余额不足，或者请求过于频繁被封禁了


## DeepSeek - 大厂直供

DeepSeek 官方平台：

https://platform.deepseek.com/

DeepSeek API 文档：

https://api-docs.deepseek.com/zh-cn/

DeepSeek API Key 获取入口：

https://platform.deepseek.com/api_keys

推荐模型：

* `deepseek-flash`

大致流程如下：

1. 打开 DeepSeek 官方平台并注册/登录账号
2. 进入 API Key 页面创建新的 Key
3. 复制生成好的 API Key
4. 回到软件设置页，选择「OpenAI 兼容」，填写 API 地址、API Key 和模型名称
5. 模型名称优先填写 `deepseek-flash`

填写建议：

* API 地址可填写 `https://api.deepseek.com/v1`
* 模型名称推荐填写 `deepseek-flash`
