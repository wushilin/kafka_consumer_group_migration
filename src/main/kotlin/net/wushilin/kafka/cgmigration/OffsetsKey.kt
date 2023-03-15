package net.wushilin.kafka.cgmigration

data class OffsetsKey(val consumerGroup:String, val topic:String, val partition:Int){
}