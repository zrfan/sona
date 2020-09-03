#!/usr/bin/env bash

mvn clean install -Dmaven.test.skip=true

#unzip ./dist/target/sona-0.1.1-bin.zip

rm -r /data10/home/weibo_bigdata_push/zhongrui3/rsync/sona/sona-0.1.1-bin/lib
mv ./dist/target/sona-0.1.1-bin/lib   /data10/home/weibo_bigdata_push/zhongrui3/rsync/sona/sona-0.1.1-bin/


hdfs dfs -rm -r zhongrui3/depend/sona-0.1.1-bin
hdfs dfs -put /data10/home/weibo_bigdata_push/zhongrui3/rsync/sona/sona-0.1.1-bin zhongrui3/depend/
