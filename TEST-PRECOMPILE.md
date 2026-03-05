
# VALIDATE PRECOMPILATE FALCON

Export enviroments

```bash
GOOD=$(jq -r '.[0].Input' evm/src/test/resources/org/hyperledger/besu/evm/precompile/falconBenchVectors.json)

IDX=$((0x108))

BAD="${GOOD:0:$IDX}f${GOOD:$((IDX+1))}"
```

## Good
```bash
curl -s http://localhost:8545 \
    -H "Content-Type: application/json" \
    --data "{
      \"jsonrpc\":\"2.0\",
      \"id\":1,
      \"method\":\"eth_call\",
      \"params\":[
        {
          \"to\":\"0x0000000000000000000000000000000000000065\",
          \"data\":\"0x$GOOD\"
        },
        \"latest\"
      ]
    }"
```
- result = 0x000...000 -> sign validate.
### bad
```bash
    curl -s http://localhost:8545 \
    -H "Content-Type: application/json" \
    --data "{
      \"jsonrpc\":\"2.0\",
      \"id\":1,
      \"method\":\"eth_call\",
      \"params\":[
        {
          \"to\":\"0x0000000000000000000000000000000000000065\",
          \"data\":\"0x$BAD\"
        },
        \"latest\"
      ]
    }"
 ```

- result = 0x000...001 -> precompile active, sign invalid.

- result = 0x -> precompile is not active in that block/schedule.


### log  message
- TRACE | AbstractBLS12PrecompiledContract | Falcon-512 verify:
- DEBUG | AbstractBLS12PrecompiledContract | Signature is VALID
- DEBUG | AbstractBLS12PrecompiledContract | Signature is INVALID


### Compilaciones de validación
./gradlew :datatypes:compileJava :evm:compileJava :ethereum:core:compileJava -x test
./gradlew :datatypes:compileJava :ethereum:core:compileJava -x test
./gradlew :ethereum:core:compileJava -x test

### gas price
```bash
curl -X POST http://localhost:8545 \
-H "Content-Type: application/json" \
--data '{
"jsonrpc":"2.0",
"method":"eth_gasPrice",
"params":[],
"id":1
}'
{"jsonrpc":"2.0","id":1,"result":"0x0"}%
```




