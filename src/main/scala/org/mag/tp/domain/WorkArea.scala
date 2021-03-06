package org.mag.tp.domain

import akka.actor.{Actor, ActorRef, Props, Terminated}
import akka.routing.{ActorRefRoutee, RandomRoutingLogic, Router}
import com.softwaremill.tagging.{@@, Tagger}
import org.mag.tp.domain.employee.{Employee, Group}
import org.mag.tp.util.actor.Pausing.{Pause, Resume}
import org.mag.tp.util.actor.{MandatoryBroadcasts, PartialBroadcasts, Pausing}

import scala.collection.{immutable, mutable}

object WorkArea {
  // messages
  sealed trait Action {
    def employee: ActorRef

    def group: Group
  }
  case class Work(employee: ActorRef, group: Group) extends Action
  case class Loiter(employee: ActorRef, group: Group) extends Action

  type ActionType = Class[_ <: WorkArea.Action]
}

class WorkArea(val groups: immutable.Seq[Group],
               val visibility: Int,
               val employeePropsFactory: (ActorRef @@ WorkArea, Group) => (Props @@ Employee),
               // FIXME: consider crashes!
               val mandatoryBroadcastables: Traversable[ActorRef])
  extends Actor with PartialBroadcasts with MandatoryBroadcasts with Pausing {

  var nextId = 0
  var employeesPerGroup = mutable.Map(groups.map(_ -> mutable.Set[ActorRef]()): _*)

  var employees: Router = {
    val targetEmployeeCounts = groups map (_.targetSize)
    val employees = Vector.fill(targetEmployeeCounts.sum) {
      ActorRefRoutee(hireEmployee())
    }
    Router(RandomRoutingLogic(), employees)
  }

  def partiallyBroadcastables: Router = employees

  def receive: Receive = respectPauses orElse {
    case Terminated(employee) =>
      employees = employees.removeRoutee(employee)
      val Some(groupEmployees) = employeesPerGroup collectFirst {
        case (_, employees) if employees.contains(employee) => employees
      }
      groupEmployees -= employee
      val newEmployee = hireEmployee()
      employees = employees.addRoutee(newEmployee)

    case msg: Any =>
      partialBroadcast(msg)
      mandatoryBroadcast(msg)
  }

  private[this] def hireEmployee(): ActorRef = {
    val groupsToComplete = employeesPerGroup filter {
      case (group, groupEmployees) => groupEmployees.size < group.targetSize
    }
    val (leastPopulatedGroup, employeesInLeastPopulatedGroup) = groupsToComplete.minBy {
      case (_, employees) => employees.size
    }
    val employeeProps = employeePropsFactory(self.taggedWith[WorkArea], leastPopulatedGroup)
    val employee = context.actorOf(employeeProps) //, s"employee-$nextId")

    context.watch(employee)
    employeesInLeastPopulatedGroup += employee
    nextId += 1
    employee
  }

  override def onPauseStart(): Unit = {
    super.onPauseStart()
    partiallyBroadcastables.routees.foreach(_.send(Pause, sender))
  }

  override def onPauseEnd(): Unit = {
    super.onPauseEnd()
    partiallyBroadcastables.routees.foreach(_.send(Resume, sender))
  }
}
