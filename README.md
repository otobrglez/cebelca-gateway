# (Unofficial) Čebelca BIZ Gateway

The gateway is a [GraphQL API](https://graphql.org/) interface that acts as an overlay over the official [Čebelca BIZ API (docs)](https://www.cebelca.biz/center-integracija-avtomatizacija.html).

With sophisticated composition, batching, pipelining, and [ZQuery](https://github.com/zio/zio-query) optimisations, it provides a type-safe and highly performant interface for common interactions with Čebelca.

The gateway is designed to serve **all tenants**, meaning you only need an API key to interact with it. 

You can obtain an API key via ["Nastavitve -> API dostop"](https://www.cebelca.biz/manage/access.html)

# Production

The recent version of the gateway with UI is auto-deployed at: [`cebelca-gateway.pinkstack.com`](https://cebelca-gateway.pinkstack.com).

- GraphQL API endpoint: `https://cebelca-gateway.pinkstack.com/api/graphql`
- UI: `https://cebelca-gateway.pinkstack.com/graphiql`

# GraphQL Schema

The up-to-date GraphQL schema can be found at the top of this folder: [`schema.graphql`](./schema.graphql). Additional configuration for GraphQL clients is also available via [`graphql.config.yml`](./graphql.config.yml) - i.e. environments and endpoints.

The schema is auto-generated and thus up-to-date with this repository. If manual regeneration is needed, use:

```bash
./mill schema-gen.generate
```
