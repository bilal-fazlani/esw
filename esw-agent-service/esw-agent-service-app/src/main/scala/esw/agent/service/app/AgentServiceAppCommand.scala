package esw.agent.service.app

import caseapp.{CommandName, ExtraName, HelpMessage}

sealed trait AgentServiceAppCommand

object AgentServiceAppCommand {

  @CommandName("start")
  final case class StartCommand(
      @ExtraName("p")
      @HelpMessage(
        "optional argument: port on which HTTP server will be bound. " +
          "If a value is not provided, it will be randomly picked."
      )
      port: Option[Int]
  ) extends AgentServiceAppCommand
}
