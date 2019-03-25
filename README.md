[![Gitter chat](https://badges.gitter.im/emc-mongoose.png)](https://gitter.im/emc-mongoose)
[![CI status](https://gitlab.com/emc-mongoose/mongoose/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose/commits/master)
[![Docker Pulls](https://img.shields.io/docker/pulls/emcmongoose/mongoose.svg)](https://hub.docker.com/r/emcmongoose/mongoose/)

# Introduction

Legacy [Mongoose](https://github.com/emc-mongoose/mongoose-base) repo, currently used for the default bundle which
includes the basic functionalityu and some historically established extensions.

# Components

| Repo | Description | Included | Version | CI status | Issues |
|------|-------------|---------------|---------|--------|--------|
| [mongoose-**base**](https://github.com/emc-mongoose/mongoose-base) | Mongoose storage performance testing tool - base functionality | :heavy_check_mark: | ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-base/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-base/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-base/commits/master) | https://mongoose-issues.atlassian.net/projects/BASE
| [mongoose-load-step-**pipeline**](https://github.com/emc-mongoose/mongoose-load-step-pipeline) | Load operations pipeline (create,delay,read-then-update, for example), extension | :heavy_check_mark: |  ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-load-step-pipeline/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-load-step-pipeline/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-load-step-pipeline/commits/master) | https://mongoose-issues.atlassian.net/projects/BASE
| [mongoose-load-step-**weighted**](https://github.com/emc-mongoose/mongoose-load-step-weighted) | Weighted load extension, allowing to generate 20% write and 80% read operations, for example | :heavy_check_mark: |  ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-load-step-weighted/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-load-step-weighted/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-load-step-weighted/commits/master) | https://mongoose-issues.atlassian.net/projects/BASE
| [mongoose-storage-driver-**coop**](https://github.com/emc-mongoose/mongoose-storage-driver-coop) | Cooperative multitasking storage driver primitive, utilizing [fibers](https://github.com/akurilov/fiber4j) | :heavy_check_mark: |  ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-coop/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-storage-driver-coop/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-storage-driver-coop/commits/master) | https://mongoose-issues.atlassian.net/projects/BASE
| [mongoose-storage-driver-**preempt**](https://github.com/emc-mongoose/mongoose-storage-driver-preempt) | Preemptive multitasking storage driver primitive, using thread-per-task approach for the I/O | :heavy_check_mark: |  ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-preempt/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-storage-driver-preempt/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-storage-driver-preempt/commits/master) | https://mongoose-issues.atlassian.net/projects/BASE
| [mongoose-storage-driver-**netty**](https://github.com/emc-mongoose/mongoose-storage-driver-netty) | [Netty](https://netty.io/)-storage-driver-nettyd storage driver primitive, extends the cooperative multitasking storage driver primitive | :heavy_check_mark: |  ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-netty/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-storage-driver-netty/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-storage-driver-netty/commits/master) | https://mongoose-issues.atlassian.net/projects/BASE
| [mongoose-storage-driver-**nio**](https://github.com/emc-mongoose/mongoose-storage-driver-nio) | Non-blocking I/O storage driver primitive, extends the cooperative multitasking storage driver primitive | :heavy_check_mark: |  ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-nio/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-storage-driver-nio/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-storage-driver-nio/commits/master) | https://mongoose-issues.atlassian.net/projects/BASE
| [mongoose-storage-driver-**http**](https://github.com/emc-mongoose/mongoose-storage-driver-http) | HTTP storage driver primitive, extends the Netty-storage-driver-httpd storage driver primitive | :heavy_check_mark: |  ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-http/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-storage-driver-http/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-storage-driver-http/commits/master) | https://mongoose-issues.atlassian.net/projects/BASE
| [mongoose-storage-driver-**fs**](https://github.com/emc-mongoose/mongoose-storage-driver-fs) | [VFS](https://www.oreilly.com/library/view/understanding-the-linux/0596005652/ch12s01.html) storage driver, extends the NIO storage driver primitive | :heavy_check_mark: |  ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-fs/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-storage-driver-fs/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-storage-driver-fs/commits/master) | https://mongoose-issues.atlassian.net/projects/BASE
| [mongoose-storage-driver-**hdfs**](https://github.com/emc-mongoose/mongoose-storage-driver-hdfs) | [Apache HDFS](http://hadoop.apache.org/docs/stable/hadoop-project-dist/hadoop-hdfs/HdfsDesign.html) storage driver, extends the NIO storage driver primitive | :x: |  ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-hdfs/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-storage-driver-hdfs/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-storage-driver-hdfs/commits/master) | https://mongoose-issues.atlassian.net/projects/HDFS
| [mongoose-storage-driver-**atmos**](https://github.com/emc-mongoose/mongoose-storage-driver-atmos) | [Dell EMC Atmos](https://poland.emc.com/collateral/software/data-sheet/h5770-atmos-ds.pdf) storage driver, extends the HTTP storage driver primitive | :heavy_check_mark: | ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-atmos/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-storage-driver-atmos/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-storage-driver-atmos/commits/master) | https://mongoose-issues.atlassian.net/projects/BASE
| [mongoose-storage-driver-**s3**](https://github.com/emc-mongoose/mongoose-storage-driver-s3) | [Amazon S3](https://docs.aws.amazon.com/en_us/AmazonS3/latest/API/Welcome.html) storage driver, extends the HTTP storage driver primitive | :heavy_check_mark: | ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-s3/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-storage-driver-s3/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-storage-driver-s3/commits/master) | https://mongoose-issues.atlassian.net/projects/S3
| [mongoose-storage-driver-**swift**](https://github.com/emc-mongoose/mongoose-storage-driver-swift) | [OpenStack Swift](https://wiki.openstack.org/wiki/Swift) storage driver, extends the HTTP storage driver primitive | :heavy_check_mark: | ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-swift/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-storage-driver-swift/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-storage-driver-swift/commits/master) | https://mongoose-issues.atlassian.net/projects/SWIFT
| [mongoose-storage-driver-**pravega**](https://github.com/emc-mongoose/mongoose-storage-driver-pravega) | [Pravega](http://pravega.io) storage driver, extends the cooperative multitasking storage driver primitive | :x: | ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-pravega/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-storage-driver-pravega/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-storage-driver-pravega/commits/master) | https://mongoose-issues.atlassian.net/projects/PRAVEGA
| [mongoose-storage-driver-**kafka**](https://github.com/emc-mongoose/mongoose-storage-driver-kafka) | [Apache Kafka](https://kafka.apache.org/) storage driver, extends the cooperative multitasking storage driver primitive | :x: | ![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-kafka/maven-metadata.xml.svg?color=green&label=%20&style=flat-square) | [![CI status](https://gitlab.com/emc-mongoose/mongoose-storage-driver-kafka/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-storage-driver-kafka/commits/master) | https://mongoose-issues.atlassian.net/projects/KAFKA
| mongoose-storage-driver-**pulsar** | [Apache Pulsar](https://pulsar.apache.org/) storage driver | :x: | TODO
| mongoose-storage-driver-**zookeeper** | [Apache Zookeeper](https://zookeeper.apache.org/) storage driver | :x: | TODO
| mongoose-storage-driver-**bookkeeper** | [Apache BookKeeper](https://bookkeeper.apache.org/) storage driver | :x: | TODO
| mongoose-storage-driver-**rocksdb** | [RocksDB](https://rocksdb.org/) storage driver | :x: | TODO
| mongoose-storage-driver-**gcs** | [Google Cloud Storage](https://cloud.google.com/storage/docs/json_api/v1/) driver | :x: | TODO
| mongoose-storage-driver-**graphql** | [GraphQL](https://graphql.org/) storage driver | :x: | TODO
| mongoose-storage-driver-**jdbc** | [JDBC](https://docs.oracle.com/javase/8/docs/technotes/guides/jdbc/) storage driver | :x: | TODO

