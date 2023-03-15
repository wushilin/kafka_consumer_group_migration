package net.wushilin.kafka.cgmigration

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import net.wushilin.props.EnvAwareProperties
import org.apache.kafka.clients.admin.*
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.IllegalArgumentException

class Main : CliktCommand() {
    companion object {
        val logger: Logger = LoggerFactory.getLogger(Main::class.java)
        var requireStable = true
        val offsetsMemory = mutableMapOf<OffsetsKey, Long>()
    }

    private val migrationFile: String by option(
        "-m",
        "--migration",
        "--migration-file",
        help = "Properties for consumer group offset migration file (see examples/migration.properties)"
    ).required()
    private val clientFile: String by option(
        "-c",
        "--command-config",
        help = "Your kafka connectivity client properties"
    ).required()

    override fun run() {
        val props = EnvAwareProperties.fromPath(clientFile)
        val admin = KafkaAdminClient.create(props)
        val config = EnvAwareProperties.fromPath(migrationFile)
        val setsString = config.getProperty("migration.targets")
        val loops = config.getProperty("loops", "1").toInt()
        val intervalMs = config.getProperty("interval.ms", "3000").toLong()
        var counter = 0
        if (setsString == null || setsString.trim().isEmpty()) {
            logger.info("Invalid `migration.targets` (null)")
            return;
        }
        if ("false".equals(config.getProperty("require.stable"), true)) {
            logger.info("Requesting unstable offsets (consumed but not committed)")
            requireStable = false
        } else {
            requireStable = true
            logger.info("Requesting stable offsets (consumed and committed)")
        }
        val sets = setsString.split(",")
        while (counter++ < loops || loops < 0) {
            for (set in sets) {
                var setTrimmed = set.trim()
                var localConfig = config.partition(setTrimmed)
                if ("false".equals(localConfig.getProperty("enable"), true)) {
                    logger.info("Ignoring set `${setTrimmed}` because it is not enabled ($setTrimmed.enabled = false)")
                    continue;
                }
                try {
                    runSet(setTrimmed, localConfig, admin)
                } catch (ex: Throwable) {
                    logger.error("Failed to run set `$setTrimmed`: $ex")
                }
            }
            logger.info("Loop $counter completed (max $loops)")
            if (counter < loops || loops < 0) {
                logger.debug("Sleeping for $intervalMs milliseconds between loops.")
                Thread.sleep(intervalMs)
            }
        }
    }

    fun runSet(set: String, config: EnvAwareProperties, admin: AdminClient) {
        //group.regex
        //group.rename
        //topic.regex
        //topic.rename
        logger.info("Running set `$set`")
        try {
            val groupRegex = config.getProperty("group.regex")
            val groupRename = config.getProperty("group.rename")
            val topicRegex = config.getProperty("topic.regex", ".*")
            val topicRename = config.getProperty("topic.rename", "\${0}")
            val groupBlacklistRegex = config.getProperty("group.blacklist.regex")
            val topicBlackListRegex = config.getProperty("topic.blacklist.regex")

            logger.info("  group.regex   => $groupRegex")
            logger.info("  group.rename  => $groupRename")
            logger.info("  topic.regex   => $topicRegex")
            logger.info("  topic.rename  => $topicRename")
            logger.info("  group.blacklist.regex => $groupBlacklistRegex")
            logger.info("  topic.blacklist.regex => $topicBlackListRegex")

            val allNames = getAllConsumerGroups(admin)
            if (groupRegex == null || groupRegex.trim().isBlank()) {
                logger.info("Skipped set $set because no group regex defined.")
                return
            }
            if (groupRename == null || groupRename.trim().isBlank()) {
                logger.info("Skipped set $set because no rename rule defined")
                return
            }
            val groupRegexP = Regex(groupRegex)
            val topicRegexP = Regex(topicRegex)
            var groupBlacklistRegexP: Regex? = null
            if (groupBlacklistRegex != null && groupBlacklistRegex.trim().isNotBlank()) {
                groupBlacklistRegexP = Regex(groupBlacklistRegex)
            }
            var topicBlacklistRegexP: Regex? = null
            if (topicBlackListRegex != null && topicBlackListRegex.trim().isNotBlank()) {
                topicBlacklistRegexP = Regex(topicBlackListRegex)
            }
            val groupMatchResult = mutableMapOf<String, Map<Int, String>>()
            val groupsToDescribe = mutableListOf<String>()
            logger.info("All Consumer Groups: ")
            for (group in allNames) {
                logger.info("   $group")
            }
            for (nextGroup in allNames) {
                logger.debug("Looking at consumer group $nextGroup")
                var matchesBlackList = false
                if (groupBlacklistRegexP != null) {
                    matchesBlackList = groupBlacklistRegexP.matches(nextGroup)
                }
                if (matchesBlackList) {
                    logger.debug("Group $nextGroup is not included because matching blacklist: $groupBlacklistRegex")
                    continue
                }
                val tokens = populateMatchGroups(nextGroup, groupRegexP)
                if (tokens.isNotEmpty() && !matchesBlackList) {
                    logger.info("+ [$nextGroup]")
                    groupsToDescribe.add(nextGroup)
                    // matched
                    groupMatchResult[nextGroup] = tokens
                } else {
                    logger.debug("Consumer group $nextGroup not included because not matching inclusion whitelist")
                }
            }

            if (groupsToDescribe.isEmpty()) {
                logger.info("No consumer group matched the regex $groupRegex")
                return
            }
            logger.info("Getting offset for Consumer Groups:")
            for (i in groupsToDescribe) {
                logger.info("  - $i")
            }
            val describeResult = listConsumerOffsets(admin, groupsToDescribe)
            if (describeResult == null) {
                logger.info("No group offset found!")
                return
            }
            for (nextGroup in describeResult.keys) {
                val newGroup = transform(groupRename, groupMatchResult[nextGroup]!!)
                val offsetInfo = describeResult[nextGroup]!!
                val consumerGroupToAlter = mutableMapOf<TopicPartition, OffsetAndMetadata>()
                for (partitionInfo in offsetInfo.keys) {
                    val partition = partitionInfo.partition()
                    val topic = partitionInfo.topic()
                    val offset = offsetInfo[partitionInfo]!!.offset()
                    val tokens = populateMatchGroups(topic, topicRegexP)
                    if (tokens.isEmpty()) {
                        logger.debug("($nextGroup) Topic $topic SKIP (not $topicRegex)")
                        continue
                    }
                    var matchedTopicBlackList = false
                    if (topicBlacklistRegexP != null) {
                        matchedTopicBlackList = topicBlacklistRegexP.matches(topic)
                    }
                    if (matchedTopicBlackList) {
                        logger.debug("($nextGroup) Topic $topic SKIP(is $topicBlackListRegex)")
                        continue
                    }
                    val newTopicName = transform(topicRename, tokens)
                    logger.debug("($nextGroup) Adding $newGroup -> $newTopicName:$partition to $offset")
                    val newTopicPartition = TopicPartition(newTopicName, partition)
                    val newOffset = offset
                    consumerGroupToAlter[newTopicPartition] = OffsetAndMetadata(newOffset)
                }
                if (consumerGroupToAlter.isNotEmpty()) {
                    val altered = alterConsumerOffsets(admin, newGroup, consumerGroupToAlter)
                    if (altered > 0) {
                        logger.info("Altered $altered offsets for $newGroup")
                    } else {
                        logger.info("No updates for $newGroup")
                    }
                }
            }
        } finally {
            logger.info("Done for set `$set`")
        }
    }

    fun alterConsumerOffsets(
        admin: AdminClient,
        consumerGroup: String,
        modifications: MutableMap<TopicPartition, OffsetAndMetadata>
    ): Int {
        if (modifications.isEmpty()) {
            return 0
        }
        try {
            modifications.entries.removeIf {
                val topic = it.key.topic()
                val partition = it.key.partition()
                val key = OffsetsKey(consumerGroup, topic, partition)
                if (offsetsMemory.containsKey(key)) {
                    val lastOffset = offsetsMemory[key]
                    val currentOffset = it.value.offset()
                    lastOffset != null && currentOffset == lastOffset
                } else {
                    false
                }
            }
            if (modifications.isNotEmpty()) {
                logger.info("Altering Consumer Group Offset: $consumerGroup:")
                for (i in modifications.entries) {
                    val key = OffsetsKey(consumerGroup, i.key.topic(), i.key.partition())
                    val previousValue = offsetsMemory[key]
                    logger.info("  ${i.key} => ${i.value.offset()} (was $previousValue)")
                }
                admin.alterConsumerGroupOffsets(consumerGroup, modifications).all().get()
                logger.info("Done")
                for (i in modifications.entries) {
                    val topic = i.key.topic()
                    val partition = i.key.partition()
                    val offset = i.value.offset()
                    val offsetsKey = OffsetsKey(consumerGroup, topic, partition)
                    offsetsMemory[offsetsKey] = offset
                }
                return modifications.size
            }
            return 0
        } catch (ex: Throwable) {
            logger.error("Failed to alter consumer group offsets: $consumerGroup => $modifications : ex")
            return 0
        }
    }

    fun transform(input: String, tokens: Map<Int, String>): String {
        if (tokens.isEmpty()) {
            logger.info("Transform has no tokens!")
            return input
        }
        logger.debug("Transforming $input with tokens $tokens")
        val regex = "\\\$" + "\\{" + "(\\d+)" + "\\}"
        val pattern = Regex(regex)
        val result = pattern.replace(input) {
            val g1 = it.groups[1]!!.value.toInt()
            val replace = tokens[g1]
            if (replace == null) {
                logger.error("Unknown group $g1 in pattern $input")
                throw IllegalArgumentException("Unknown group $g1 in pattern $input")
            }
            replace
        }
        logger.debug("Result: $result")
        return result
    }

    fun populateMatchGroups(input: String, regex: Regex): Map<Int, String> {
        logger.debug("Populating match groups for $input with regex ${regex.pattern}")
        val result = mutableMapOf<Int, String>()
        val matchResult = regex.matchEntire(input) ?: return result
        for (i in matchResult.groups.indices) {
            val key = i
            val value = matchResult.groups[i]!!.value
            result[key] = value
        }
        logger.debug("Result: $result")
        return result
    }

    fun listConsumerOffsets(
        admin: AdminClient,
        groups: List<String>
    ): MutableMap<String, MutableMap<TopicPartition, OffsetAndMetadata>>? {
        val options = ListConsumerGroupOffsetsOptions()
        options.timeoutMs(60000)
        options.requireStable(requireStable)
        val spec = mutableMapOf<String, ListConsumerGroupOffsetsSpec>()
        for (groupId in groups) {
            val specArg = ListConsumerGroupOffsetsSpec()
            spec[groupId] = specArg
        }
        return admin.listConsumerGroupOffsets(spec, options).all().get()
    }

    fun getAllConsumerGroups(admin: AdminClient): List<String> {
        val options = ListConsumerGroupsOptions()
        options.timeoutMs(60000)
        val cgs = admin.listConsumerGroups(options).all().get()
        val result = mutableListOf<String>()
        for (cg in cgs) {
            val gid = cg.groupId()
            if (gid != null) {
                result.add(gid.trim())
            }
        }
        return result
    }
}

fun main(args: Array<String>) = Main().main(args)
