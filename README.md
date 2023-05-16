# OpenHIM Common

Edit the `default.json` file to point to the **public** host and port of your server. If you are just testing on localhost you can leave it set at the defaults.

Spin up the OpenHIM core and console easily with docker compose:

```
docker-compose build && docker-compose up -d
```

Access on localhost:9000 or at <your_server>:9000

## Note
1. Launch the web app on port 9000
2. For local deployements, under the `default.json` file change `host` in line 6 to the server IP
    ``` 
        eg from "host": "localhost",
            to "host": "10.35.160.65",
    ```
## Runtime configs

java -jar target/openhim-mediator-hl7message-handler-1.0.0-jar-with-dependencies.jar --conf mediator.properties --regConf mediator-registration-info.json