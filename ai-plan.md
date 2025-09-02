
Need to make an HTTP request.

Needs the follwoing
- Headers
    - Bearer token
- json payload

```
{
"model": model,
"max_tokens": 1024,
"messages": [
{"role": "assisant", "contnet": "you are helpful"}.
{"role": "user", "contnet": "knoch knco"}
]
}
```

This gets posted. The url and bearer token can be configured
