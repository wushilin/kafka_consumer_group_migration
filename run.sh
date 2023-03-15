#!/bin/sh

CP=`find . -name 'kafka_consumer*.jar'`
echo Classpath: $CP
java -cp $CP net.wushilin.kafka.cgmigration.MainKt $*
