# OpenSearch SDK
- [Introduction](#introduction)
- [Contributing](#contributing)

## Introduction
Opensearch plugins have allowed the extension and ehancements of various core features however, current plugin architecture carries the risk of fatally impacting clusters should they fail. In order to ensure that plugins may run safely without impacting the system, our goal is to effectively isolate plugin interactions with OpenSearch by modularizing the [extension points](https://opensearch.org/blog/technical-post/2021/12/plugins-intro/) to which they hook onto.

Read more about extensibility [here](https://github.com/opensearch-project/OpenSearch/issues/1422)

## Contributing
See [Developer Guide](DEVELOPER_GUIDE.md)
