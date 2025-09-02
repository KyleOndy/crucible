
Need to make an HTTP request.

Needs the following
- Headers
    - Bearer token
- json payload

```
{
"model": model,
"max_tokens": 1024,
"messages": [
{"role": "assistant", "content": "you are helpful"},
{"role": "user", "content": "knoch knco"}
]
}
```

This gets posted. The url and bearer token can be configured
