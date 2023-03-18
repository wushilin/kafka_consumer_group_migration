# kafka_consumer_group_migration
A tool help you to do consumer group migration.

You can migrate offsets on the same cluster, or different cluster.

You can migrate offset with consumer group renaming, and/or topic renaming.

You can run it once, or run it continuously until you stop it.


# Goal
This tool is useful when:
1. You want to adjust consumer group offset according to another consumer group offset
2. Rename consumer group with offset migration

You can't adjust consumer groups that is currently active (have consumers attached)

You can adjust this as one off (`loops=1`), or run forever (`loops=-1`)

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
  
- Run `run.sh -s src-client.properties -d dest-client.properties -m migration.properties`

```bash
Source cluster specified by -s src-client.properties
Dest cluster specified by -d dest-client.properties

If -d is not given, source cluster to source cluster migration (rename) is assumed
```
- To get help, run `run.sh --help`
```bash
Usage: main [OPTIONS]

Options:
  -m, --migration, --migration-file TEXT
                                   Properties for consumer group offset
                                   migration file (see
                                   examples/migration.properties)
  -s, --src-command-config TEXT    Your source kafka connectivity client
                                   properties
  -d, --dest-command-config TEXT   Your destination kafka connectivity client
                                   properties (default = source cluster)
  -h, --help                       Show this message and exit

```

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
# In this example, consumer groups that starts with _confluent-control (e.g. _confluent-control-test1) will be 
# translated to a group called NewGroup--test1 because ${1} is "-test1"
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
# The topic of the consumer group is untouched. You can use tempalte similar to `set1.group.rename`
# to do a name translation of the topic as well. Not very much useful, but it provides flexibility
set1.topic.rename=${0}

# Definition for set2
set2.xxxx=xxxx
# set2 will be literally ignored
set2.enable=false
...
```
