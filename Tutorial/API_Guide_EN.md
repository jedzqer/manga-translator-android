# API Setup Guide

> This guide is maintained for the latest version of the app. Earlier settings screens may differ. Check your provider's dashboard for current URLs and model IDs.

Manga Translator requires your own AI provider API to translate manga pages. This guide covers DeepSeek and OpenAI. It does not promote region-specific third-party providers.

## What you need

Before opening the app's **Settings** screen, prepare:

1. An API format
2. An API URL
3. An API key
4. A model name

Use the provider's base URL, not a complete request endpoint, unless the provider explicitly gives you one. The app completes its endpoint automatically:

- **OpenAI compatible**: appends `/chat/completions` unless the URL already ends with it
- **OpenAI Responses**: appends `/responses` unless the URL already ends with it
- **Gemini**: uses its own API endpoint format
- **Get Model List**: uses `/models` for either OpenAI format and the corresponding Gemini endpoint for Gemini

An API key is a secret credential generated in the provider dashboard. Never share it in screenshots, chat messages, or source code.

## DeepSeek

DeepSeek provides an OpenAI-compatible API.

- Platform: <https://platform.deepseek.com/>
- API key page: <https://platform.deepseek.com/api_keys>
- Documentation: <https://api-docs.deepseek.com/>

### Get a key

1. Create or sign in to a DeepSeek Platform account.
2. Open the API key page and create a new key.
3. Copy the key when it is shown, then store it securely.
4. Ensure the account has the required access and balance before translating a large folder.

### Enter the settings in Manga Translator

| App setting | Value |
| --- | --- |
| API Format | **OpenAI compatible** |
| API URL | `https://api.deepseek.com` |
| API Key | The key created in DeepSeek Platform |
| Model name | `deepseek-flash` |

The app sends requests to the OpenAI-compatible chat-completions endpoint. You can select **Get Model List** to see models exposed by your account, and should use the exact model ID that DeepSeek provides.

## OpenAI

OpenAI's current models are available through the Responses API. Manga Translator supports this format directly.

- API dashboard and key management: <https://platform.openai.com/api-keys>
- Official quickstart: <https://developers.openai.com/api/docs/quickstart>
- Model catalog: <https://developers.openai.com/api/docs/models>

### Get a key

1. Sign in to the OpenAI Platform dashboard.
2. Open **API Keys** and create a new secret key.
3. Copy the key immediately and store it in a secure password manager or another private location.
4. Review the platform's billing and usage settings before translating a large folder.

### Enter the settings in Manga Translator

| App setting | Value |
| --- | --- |
| API Format | **OpenAI Responses** |
| API URL | `https://api.openai.com/v1` |
| API Key | The secret key created in the OpenAI Platform dashboard |
| Model name | `gpt-5.6` |

With this configuration, the app appends `/responses` to the base URL. The official OpenAI quickstart currently uses `gpt-5.6`; use the model catalog or **Get Model List** to choose another model available to your account.

## Troubleshooting

- If **Get Model List** fails, verify the API URL, format, key, account access, and network connection.
- An authentication error usually means the key is invalid, revoked, or pasted with extra spaces.
- A model-not-found error means the model name is unavailable to the account or has been mistyped. Copy the exact model ID from the provider dashboard.
- Rate-limit, billing, or quota errors must be resolved in the provider dashboard. Reducing app concurrency can help prevent repeated rate-limit errors.
