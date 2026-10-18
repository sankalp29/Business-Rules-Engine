<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->

# Rule engine server

HTTP service that stores fact types, authors a rule as JSON when/then blocks, compiles that JSON to DRL, stores each version of the rule in PostgreSQL, and executes the version whose effective dates include the moment of the call. A rule is addressed by its `code`.

This module is standalone. It is not part of the Drools reactor build. Run the commands below from `rule-engine-server/`.

A type is defined once, with its fields. A rule names those types in its `when` blocks. The server does not store a `facts` list on the rule. At authoring time it computes `inputSchema` and `outputSchema`: a field a condition reads is in both, and a field a `then` assignment sets is in `outputSchema`. The generated DRL still declares the full type, so a later edit to a type does not change a saved rule, and execution still accepts every field of that type. A `when` condition is an object: a field, an operator, and either a value or another field. Several conditions combine with `and` and `or`, and those groups can be nested. The value's JSON type must match the field type. The `then` assignments set scalar fields, including fields nested inside another fact. Execution compares every fact field before and after the rule runs and returns a `reasoning` string that names each field that changed.

`expression` assignments are compiled as rule code. Expose the authoring API only to trusted authors.

## Run the application

Requires Java 17, 21, or 25 (Java 21 is the version these steps use), Maven, and Docker.

```bash
cd rule-engine-server
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
docker compose up -d --wait
mvn spring-boot:run
```

The API listens on `http://localhost:8080`. PostgreSQL listens on `localhost:5433` with database `rules`, user `rules`, and password `rules`. The server creates the `fact_types`, `authored_rules`, and `rule_versions` tables on startup.

Host port 5433 is intentional. A local PostgreSQL often already listens on `127.0.0.1:5432`. Connections to `localhost:5432` reach that server, which has no `rules` role, even while the Compose container is healthy on Docker's published port.

Stop the server with Ctrl+C, then stop PostgreSQL:

```bash
docker compose down
```

`docker compose down -v` also deletes the saved types and rules.

If port 5433 or 8080 is already in use:

```bash
# in docker-compose.yml, change "5433:5432" to "5434:5432"
DB_URL=jdbc:postgresql://localhost:5434/rules SERVER_PORT=8081 mvn spring-boot:run
```

Run the tests (the integration test starts its own PostgreSQL container):

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```

## Define a type

`POST /api/types`

Define a type before a rule uses it. Each field has either a scalar `type` or nested `fields`, not both. A field `type` is a scalar or the name of a type that is already saved. Nesting can go 10 levels deep. A scalar-only type has depth 1, and each nested object adds one.

Scalar types are `String`, `int`, `long`, `double`, `boolean`, `Integer`, `Long`, `Double`, and `Boolean`.

```bash
curl -s -D - -X POST http://localhost:8080/api/types \
  -H 'Content-Type: application/json' \
  --data @examples/create-address-type.json

curl -s -D - -X POST http://localhost:8080/api/types \
  -H 'Content-Type: application/json' \
  --data @examples/create-customer-type.json
```

Address:

```json
{
  "name": "Address",
  "fields": [
    { "name": "city", "type": "String" },
    { "name": "zip", "type": "String" }
  ]
}
```

Customer, with a nested `address` of the Address type:

```json
{
  "name": "Customer",
  "fields": [
    { "name": "name", "type": "String" },
    { "name": "age", "type": "int" },
    { "name": "discount", "type": "double" },
    { "name": "segment", "type": "String" },
    { "name": "address", "type": "Address" }
  ]
}
```

Response `201 Created`, with `Location: /api/types/Address`. `GET /api/types` lists types. `GET /api/types/{name}` returns one type.

The same shape can nest fields inline instead of referring to a saved type:

```json
{ "name": "city", "fields": [ { "name": "name", "type": "String" } ] }
```

An inline object is declared as `{Parent}_{field}` when a rule is compiled, for example `Customer_address`. That synthetic name must not already be a type.

## Author a rule

`POST /api/rules`

`code` is the rule's identifier. It must start with a letter and contain only letters, digits, underscores, or hyphens, up to 64 characters. It cannot be all digits, so it never hides a numeric id. `name` is the rule's title. Both stay the same on every version. `when[].type` names a type that is already defined. A `facts` array on the request is ignored. `inputSchema` and `outputSchema` sent by the client are ignored too; the server computes both and stores those.

Creating a rule saves version `v1`. `effectiveFrom` and `effectiveUntil` are required. Each value is an India Standard Time calendar date (`YYYY-MM-DD`, inclusive) or an ISO-8601 timestamp (inclusive). A timestamp with no offset is read as India Standard Time. Precision is one minute: a second or a fraction is rejected with `effectiveFrom must be a whole minute` or `effectiveUntil must be a whole minute`. A calendar date uses 00:00 IST for `effectiveFrom` and 23:59 IST for `effectiveUntil`. The current minute is allowed. A minute before the current minute is rejected for either bound (`effectiveFrom cannot be before the current time`, `effectiveUntil cannot be before the current time`). `effectiveUntil` must be after `effectiveFrom`. This version runs only inside that window.

A version may list several periods in `segments`. Each item has `effectiveFrom` and `effectiveUntil`. When `segments` is present it is the full schedule, and the single pair is ignored. Periods of the same version that meet at the next minute are stored as one period. A gap between a version's own periods stays a gap. A period that sits strictly inside one period of another version splits that period. This happens when the period is saved on a new version and when it is added to a version that already exists. The other version runs again after the period ends. A partial overlap, including a shared minute, is rejected. At most one version is active at a timestamp.

Example: v2 runs from 1 Oct 00:00 IST through 10 Oct 23:59 IST. A new v3 runs on 2 Oct only, 00:00 through 23:59. v2 is then stored as 1 Oct, and again from 3 Oct through 10 Oct. On 2 Oct, v3 runs. On 3 Oct, v2 runs again. Adding 5 Oct to v3 splits v2 once more, so v2 also pauses on 5 Oct and resumes on 6 Oct.

The sample dates start at 18 Oct 2026 00:00 IST. If that moment has passed, move `effectiveFrom` to the current time or later before posting the sample.

```bash
curl -s -D - -X POST http://localhost:8080/api/rules \
  -H 'Content-Type: application/json' \
  --data @examples/create-senior-discount.json
```

Request:

```json
{
  "code": "senior-discount-without-types",
  "name": "Apply senior discount",
  "description": "Customers aged 65 or older receive a senior discount",
  "effectiveFrom": "2026-10-18",
  "effectiveUntil": "2099-12-31",
  "when": [
    {
      "binding": "$customer",
      "type": "Customer",
      "conditions": { "field": "age", "operator": ">=", "value": 65 }
    }
  ],
  "then": [
    {
      "binding": "$customer",
      "assignments": [
        { "field": "discount", "value": 0.2 },
        { "field": "segment", "value": "SENIOR" }
      ]
    }
  ]
}
```

Response `201 Created`, with `Location: /api/rules/senior-discount-without-types`:

```json
{
  "id": 1,
  "code": "senior-discount-without-types",
  "name": "Apply senior discount",
  "description": "Customers aged 65 or older receive a senior discount",
  "activeVersion": "v1",
  "definition": {
    "inputSchema": [
      { "type": "Customer", "fields": [ { "name": "age", "type": "int" } ] }
    ],
    "outputSchema": [
      {
        "type": "Customer",
        "fields": [
          { "name": "age", "type": "int" },
          { "name": "discount", "type": "double" },
          { "name": "segment", "type": "String" }
        ]
      }
    ]
  },
  "drl": "package rules.dynamic\n\n...",
  "createdAt": "2026-10-17T12:00:00Z"
}
```

`code` and `name` are returned on the rule. `description`, `definition`, and `drl` are the version that is active at the time of the response, and `activeVersion` is its token, such as `v1`. When no version is active those three fields are null. `versions` lists every version. The `definition` object is that version's body: `salience`, `inputSchema`, `outputSchema`, `when`, and `then`. `inputSchema` lists the fields the conditions read, including a `valueField` such as `$customer.name`. `outputSchema` lists those same fields plus the fields the assignments set. Nested fields stay nested, and only the used children are included. For `move-city`, both schemas contain `Customer.address.city` and omit `zip`. Field order follows the type. Fact order follows the first `when` block that uses the type. A type with no selected fields is omitted. The DRL, not the schema, declares every field of each `when` type, including `name` and `address`.

`id` is still the database id. New rules are fetched and executed by `code`. A rule saved before `code` existed has a null `code` and remains available at `/api/rules/{id}`. Both forms work for every rule: a code is tried first, and an all-digit key is then read as an id.

Generated DRL for that request:

```
package rules.dynamic

dialect "java"

declare Address
    city : String
    zip : String
end

declare Customer
    name : String
    age : int
    discount : double
    segment : String
    address : Address
end

// Customers aged 65 or older receive a senior discount
rule "Apply senior discount"
    no-loop true
when
    $customer : Customer( age >= 65 )
then
    modify( $customer ) { setDiscount( 0.2 ), setSegment( "SENIOR" ) };
end
```

A `value` is a JSON string, number, boolean, or null, and it must match the field's data type. Use `expression` instead of `value` for a Java expression, for example `"expression": "$customer.getAge() * 0.01"`. Optional `salience` is an integer. Assign a scalar field. For a nested object, assign a leaf such as `address.city` rather than the object itself.

## Conditions

`when[].conditions` is one object, not a quoted Drools expression. A comparison is `field`, `operator`, and either `value` or `valueField`.

| Operator | Fields | Right-hand side |
| --- | --- | --- |
| `==`, `!=` | any scalar | a value of that type, `null` for a non-primitive, or a `valueField` of a compatible type |
| `>`, `>=`, `<`, `<=` | numeric (`int`, `long`, `double`, and `Integer`, `Long`, `Double`) | a JSON number, or a numeric `valueField` |
| `contains`, `matches` | `String` | a JSON string, or a String `valueField`. `matches` is a regular expression |

`segment` is a String, so `">="` with `65` is rejected. Compare `age` with `65`, or compare `segment` with a string such as `"SENIOR"`. An `int` field rejects a JSON string. A whole object such as `address` is rejected; compare a leaf such as `address.city`.

`valueField` is another field of this fact (`address.zip`) or a field of a fact bound in this or an earlier `when` block (`$customer.name`).

`and` and `or` combine at least two conditions. Groups nest. The generated constraint uses `&&` and `||` and keeps that nesting in parentheses.

```bash
curl -s -D - -X POST http://localhost:8080/api/types \
  -H 'Content-Type: application/json' \
  --data @examples/create-order-type.json

curl -s -D - -X POST http://localhost:8080/api/rules \
  -H 'Content-Type: application/json' \
  --data @examples/create-large-adult-order.json

curl -s -D - -X POST http://localhost:8080/api/rules \
  -H 'Content-Type: application/json' \
  --data @examples/create-priority-customer.json
```

`create-large-adult-order.json` requires the Order type in `examples/create-order-type.json`. It joins two facts with `and`: the order name equals `$customer.name` and the total is above 100. `create-priority-customer.json` nests `and` inside `or`: a gold customer aged 65 or older, or any customer whose discount is above 0.5.

```json
{
  "operator": "or",
  "conditions": [
    {
      "operator": "and",
      "conditions": [
        { "field": "age", "operator": ">=", "value": 65 },
        { "field": "segment", "operator": "==", "value": "GOLD" }
      ]
    },
    { "field": "discount", "operator": ">", "value": 0.5 }
  ]
}
```

`GET /api/rules` lists stored rules. `GET /api/rules/senior-discount-without-types` and `GET /api/rules/1` return the same rule.

## Versions

`POST /api/rules/{code}/versions` saves the next version. The server assigns `v1`, then `v2`, and so on. The body has its own `description`, `salience`, `when`, `then`, `effectiveFrom`, and `effectiveUntil`. `code` and `name` may be omitted. If they are sent, they must match the rule; a different value is rejected with `code stays the same on every version of a rule` or `name stays the same on every version of a rule`. Send `segments` when this version runs in more than one period.

Windows are inclusive and use whole minutes. At most one version is active at a timestamp. A gap between versions is allowed. Execution during a gap fails. A period that sits strictly inside one period of an earlier version splits that period, including a period added later to a version that already exists. The earlier version pauses for that period and resumes after it. A partial overlap, including a shared minute, is rejected. The same India Standard Time rules apply: neither bound can be before the current minute, and the end must be after the start.

A version with a gap of its own sends `segments`:

```json
"segments": [
  { "effectiveFrom": "2026-11-01", "effectiveUntil": "2026-11-01" },
  { "effectiveFrom": "2026-11-03", "effectiveUntil": "2026-11-10" }
]
```

The response always includes `segments`. `effectiveFrom` is the start of the first period and `effectiveUntil` is the end of the last period, so the outer span can cover a hole. `active` is true only when the current minute falls inside a period.

```bash
curl -s -D - -X POST http://localhost:8080/api/rules/senior-discount-without-types/versions \
  -H 'Content-Type: application/json' \
  -d '{
    "description": "Senior discount for 2027 onward",
    "effectiveFrom": "2027-01-01",
    "effectiveUntil": "2028-12-31",
    "when": [
      { "binding": "$customer", "type": "Customer", "conditions": { "field": "age", "operator": ">=", "value": 60 } }
    ],
    "then": [
      { "binding": "$customer", "assignments": [ { "field": "discount", "value": 0.25 }, { "field": "segment", "value": "SENIOR" } ] }
    ]
  }'
```

Response `201 Created`, with `Location: /api/rules/senior-discount-without-types/versions/v2`.

`GET /api/rules/{code}/versions` returns every version, oldest first. `GET /api/rules/{code}/versions/v2` returns that version. `v2` and `2` name the same version.

A rule stored before versions existed is read as `v1` with no end date, so it still runs. The first version added after that ends `v1` one minute before the new version starts. That closed `v1` stays closed.

`PUT /api/rules/{code}/versions/{version}/dates` changes the effective dates of one version and leaves its description, when, then, and DRL as they are. The response is `200 OK`.

```bash
curl -s -X PUT http://localhost:8080/api/rules/senior-discount-without-types/versions/v1/dates \
  -H 'Content-Type: application/json' \
  -d '{ "effectiveFrom": "2026-10-18T00:00:00+05:30", "effectiveUntil": "2027-06-01" }'
```

Send the stored instant back when that bound can no longer move. A calendar date is a whole IST day, so it matches a stored instant only when that instant is 00:00 IST (for `effectiveFrom`) or 23:59 IST (for `effectiveUntil`).

- `effectiveFrom` cannot be before the current minute.
- `effectiveUntil` cannot be before the current minute.
- `effectiveUntil` must be after `effectiveFrom`.
- Both values are whole minutes. A new second or fraction is rejected. Sending the stored instant back is allowed when that bound is locked, including an older instant that is finer than a minute.
- If the stored `effectiveFrom` is already before the current minute, a different start is rejected: `effectiveFrom cannot change because it is already before the current time`. Sending the same instant is allowed.
- If the stored `effectiveUntil` is already before the current minute, a different end is rejected: `effectiveUntil cannot change because it is already before the current time`. An open end on a rule stored before versions existed can still be set.
- A period that sits strictly inside another version splits that version, including when the period is added to a version that already exists. The earlier version resumes after that period. A partial overlap, including a shared minute, is rejected: `effective dates overlap v2 (...)`.
- `segments` on this request replaces the whole schedule. The same locks apply to each period. A new future period can sit beside periods that are already in the past.

Setting the end on a rule that was stored before versions existed writes a real `v1` row. Its start stays at `1970-01-01T00:00:00Z`.

A nested write uses the same types:

```bash
curl -s -D - -X POST http://localhost:8080/api/rules \
  -H 'Content-Type: application/json' \
  --data @examples/create-move-city.json
```

```json
{
  "code": "move-city",
  "name": "Move customer to Pune",
  "description": "Sets the nested city on the customer address",
  "effectiveFrom": "2026-10-18",
  "effectiveUntil": "2099-12-31",
  "when": [
    {
      "binding": "$customer",
      "type": "Customer",
      "conditions": { "field": "address.city", "operator": "==", "value": "London" }
    }
  ],
  "then": [
    {
      "binding": "$customer",
      "assignments": [
        { "field": "address.city", "value": "Pune" }
      ]
    }
  ]
}
```

## Execute a rule

`POST /api/rules/{code}/execute` runs the rule. The server chooses the single version whose effective dates contain the current minute, and the response names that version. The request does not name a version. If no version contains the current minute, or more than one does, the call returns `409` and does not run. The clock is an absolute instant, and messages print that instant in India Standard Time.

```bash
curl -s -X POST http://localhost:8080/api/rules/senior-discount-without-types/execute \
  -H 'Content-Type: application/json' \
  --data @examples/execute-senior-discount.json
```

The same call works with the numeric id, for example `POST /api/rules/1/execute`.

Request:

```json
{
  "facts": [
    {
      "type": "Customer",
      "values": {
        "name": "Ada Lovelace",
        "age": 70,
        "discount": 0,
        "segment": "STANDARD",
        "address": { "city": "London", "zip": "SW1A" }
      }
    }
  ]
}
```

Response `200 OK` when the rule fires:

```json
{
  "ruleId": 1,
  "code": "senior-discount-without-types",
  "version": "v1",
  "fired": true,
  "reasoning": "Rule Apply senior discount fired. Set Customer.discount to 0.2 (was 0.0). Set Customer.segment to SENIOR (was STANDARD).",
  "inputData": [
    {
      "type": "Customer",
      "values": {
        "name": "Ada Lovelace",
        "age": 70,
        "discount": 0.0,
        "segment": "STANDARD",
        "address": { "city": "London", "zip": "SW1A" }
      }
    }
  ],
  "outputData": [
    {
      "type": "Customer",
      "values": {
        "name": "Ada Lovelace",
        "age": 70,
        "discount": 0.2,
        "segment": "SENIOR",
        "address": { "city": "London", "zip": "SW1A" }
      }
    }
  ],
  "modifiedData": [
    {
      "type": "Customer",
      "values": {
        "discount": { "previousValue": 0.0, "newValue": 0.2 },
        "segment": { "previousValue": "STANDARD", "newValue": "SENIOR" }
      }
    }
  ]
}
```

The same call with age 30 (`examples/execute-no-match.json`) does not fire:

```json
{
  "code": "senior-discount-without-types",
  "fired": false,
  "reasoning": "Rule Apply senior discount did not fire. No fields were changed.",
  "modifiedData": [
    { "type": "Customer", "values": {} }
  ]
}
```

Nested execute:

```bash
curl -s -X POST http://localhost:8080/api/rules/move-city/execute \
  -H 'Content-Type: application/json' \
  --data @examples/execute-move-city.json
```

That response sets `address.city` from `London` to `Pune`. `reasoning` is `Rule Move customer to Pune fired. Set Customer.address.city to Pune (was London).` `modifiedData` nests the change under `address.city` as `{ "previousValue": "London", "newValue": "Pune" }`.

The engine fires a rule at most once, so the response uses `fired` and does not repeat the rule name or a firing count.

`inputData`, `outputData`, and `modifiedData` list the same facts in the same order. Every item has `type` and `values`. In `inputData` and `outputData`, each value is the field value or a nested object. In `modifiedData`, a changed scalar is `{ "previousValue", "newValue" }`, nested until the leaf that changed. An empty `values` object means that fact did not change. Slashes stay in these values.

`reasoning` is plain text. It does not wrap names or values in quotes, and it omits `\` and `/` so the string never contains either character. When several facts share a type, the reasoning labels them `Customer[1]`, `Customer[2]`, in request order.

A value sent for the wrong data type is rejected. `"age": "seventy"` returns 400 with `must be a JSON number`. `"address": { "city": 12 }` returns 400 with `must be a JSON string`. `"salary": 1` returns 400 because `salary` is not a field of Customer. A condition has the same check: `{ "field": "segment", "operator": ">=", "value": 65 }` returns 400 because `>=` is not allowed for String. A missing field keeps its default and is not an error.

## Errors

| Status | When |
| --- | --- |
| 400 | The JSON shape, type, field, or value is invalid, the code is already used, or the effective dates are invalid: the start is before the current time, the end is not after the start, a start or end that is already in the past was changed, or the dates overlap another version |
| 404 | No rule has that code or id, no version has that number, or no type has that name |
| 409 | No version's dates contain the current minute, or more than one version contains it |
| 422 | The generated DRL does not compile |

```json
{ "error": "Invalid rule", "details": ["when[0].type Order is not defined"] }
```

```json
{ "error": "Invalid type", "details": ["nesting depth is 11; the maximum is 10"] }
```

```json
{ "error": "Rule not found", "details": ["No rule exists with code or id missing-rule"] }
```

```json
{ "error": "Rule version is not active", "details": ["No version of senior-discount-without-types is active at 2026-10-18T00:00:00+05:30. Versions: v1 is effective 2020-01-01T00:00:00+05:30 until 2025-12-31T23:59:00+05:30."] }
```

## Stored data

PostgreSQL table `fact_types`:

| Column | Meaning |
| --- | --- |
| `id` | Database id |
| `name` | Type name, unique |
| `definition` | JSON document that was authored |
| `created_at` | UTC timestamp |

PostgreSQL table `authored_rules`:

| Column | Meaning |
| --- | --- |
| `id` | Database id, still accepted in fetch and execute URLs |
| `code` | Rule code, unique, shared by every version. Null on rules stored before codes existed |
| `name` | Rule name, shared by every version |
| `description` | Description of the latest saved version. Execution does not read this copy |
| `definition` | JSON body of the latest saved version. A rule stored before versions existed keeps its only body here and is read as `v1` |
| `drl` | DRL of the latest saved version |
| `created_at` | UTC timestamp |

PostgreSQL table `rule_versions`:

| Column | Meaning |
| --- | --- |
| `id` | Database id of the version |
| `rule_id` | Rule this version belongs to |
| `version_number` | 1, 2, 3, … assigned by the server. The API shows it as `v1`, `v2`, `v3` |
| `description` | Optional description for this version only |
| `definition` | JSON body for this version: salience, schemas, `when`, and `then`. Code and name are not in this document |
| `drl` | DRL compiled from this version |
| `effective_from` | Start of the first period, inclusive. An absolute instant. A calendar date in the API is 00:00 India Standard Time. When `effective_segments` is set, activity reads that JSON |
| `effective_until` | End of the last period, inclusive. An absolute instant. A calendar date in the API is 23:59 India Standard Time |
| `effective_segments` | JSON array of `{effectiveFrom, effectiveUntil}` periods, or null when the version has the single window in the two columns above. A gap between periods is a time when this version does not run |
| `created_at` | UTC timestamp |
