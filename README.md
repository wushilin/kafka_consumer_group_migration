# kafka_consumer_group_migration
A tool help you to do one-time, or continuous consumer group migration


# Goal
This tool is useful when:
1. You want to adjust consumer group offset according to another consumer group offset
2. Rename consumer group with offset migration

# Requires
Java 1.8

# Building
```bash
create_package
```

Built artifact will be in build/ folder (check tar.gz file)

# Running
- Unzip the built artifact
  $ tar zxvf xxxx.tar.gz

- Change directory to the unzipped folder

- Edit `client.properties` for your kafka connectivity. The user should have consumer group rights

- Edit `migration.properties`
  
- Run `run.sh -c client.properties -m migration.properties`

- To get help, run `run.sh --help`

- Monitor the consumer group offset are updated accordingly.

# About `migration.properties`

Migration properties describes what consumer group to migration, and how.

See `examples/migration.properties`

```properties
# How many loops to run. 
# Values > 0  => Repeat $loops time
# Values < 0  => executed forever
# Values = 0  => No run
# Default: 1
loops=-1

# How many milliseconds to sleep between each loop
# Default: 3000
interval.ms=3000

# Whether to get committed offsets only or get the read but not committed offset
# Default: true
require.stable=true

# Which migration sets to run, comma separated list. Defined migration set but not added here will be ignored
# Required, No default value
migration.targets=set1,set2

# Properties are for migration set `set1`
set1.xxxx=xxx

# Whether this set is enabled or not. Defined, configured in `migration.targets` but disabled migration set will not be run
# Default: true
set1.enable=true

# What consumer groups to include
# Required, No default value
set1.group.regex=_confluent-control(.*)

# What consumer group to exclude, even when they are included in the whitelist previously
# Default: ""
set1.group.blacklist.regex=NewGroup-.*

# Consumer group name conversion rule. ${0} -> The entire old name, ${1} the first capture group in brackets and so on
# Required, No default value
set1.group.rename=NewGroup-${1}

# Topics to include for the migration by regular expression
# Default: .*
set1.topic.regex=.*-metrics-(.+)

# Topics to exclude for the migration by regular expression (higher priority)
# Default: ""
set1.topic.blacklist.regex=

# Topic rename rule if any. Default is ${0} (means equals to the old group name)
# Default: ${0}
set1.topic.rename=${0}

# Definition for set2
set2.xxxx=xxxx
# set2 will be literally ignored
set2.enable=false
...
```
