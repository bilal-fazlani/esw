package esw.sm.api.models

import csw.prefix.models.Prefix
import esw.sm.api.models.ProvisionConfig.{AgentPrefix, SequenceComponentPrefix}

case class AgentProvisionConfig(agentPrefix: Prefix, countOfSeqComps: Int) {
  // check count is greater than Zero
  require(
    countOfSeqComps > 0,
    "Invalid sequence component count: Count of sequence components must be greater than Zero"
  )
}

case class ProvisionConfig(config: List[AgentProvisionConfig]) {
  private val repeatedPrefix: List[AgentProvisionConfig] = config.diff(config.distinctBy(_.agentPrefix))
  // Check there are no double entries of any prefix
  require(
    repeatedPrefix.isEmpty,
    s"Invalid Provision config: Cannot have multiple entries for same agent prefix. :$repeatedPrefix"
  )

  def agentToSeqCompMapping: List[(AgentPrefix, SequenceComponentPrefix)] = {
    def assign(config: List[AgentProvisionConfig], from: Int): List[(AgentPrefix, SequenceComponentPrefix)] =
      config match {
        case Nil => List.empty
        case AgentProvisionConfig(agentPrefix, count) :: rest =>
          generateSeqCompPrefix(agentPrefix, count, from) ++ assign(rest, from + count)
      }

    groupPrefixBySubsystem.flatMap(assign(_, 1)).toList
  }

  private def groupPrefixBySubsystem = config.groupBy(_.agentPrefix.subsystem).view.mapValues(_.toList).values

  private def generateSeqCompPrefix(agentPrefix: AgentPrefix, count: Int, from: Int) =
    List.fill(count)(agentPrefix).zipWithIndex.map {
      case (_, i) => (agentPrefix, Prefix(agentPrefix.subsystem, s"${agentPrefix.subsystem}_${from + i}"))
    }
}

object ProvisionConfig {
  type AgentPrefix             = Prefix
  type SequenceComponentPrefix = Prefix

  def apply(config: (Prefix, Int)*): ProvisionConfig = ProvisionConfig(config.map(x => AgentProvisionConfig(x._1, x._2)).toList)
}
