package de.lolhens.edifact

import fastparse.NoWhitespace._
import fastparse._

object Edifact {

  case class ServiceStringAdvice(componentSeparatorChar: Char,
                                 elementSeparatorChar: Char,
                                 decimalMarkChar: Char,
                                 releaseChar: Char,
                                 segmentTerminatorChar: Char)

  object ServiceStringAdvice {
    final val tag = "UNA"

    val default: ServiceStringAdvice = ServiceStringAdvice(':', '+', '.', '?', '\'')
  }

  case class Element(value: String)

  case class Composite(elements: List[Element]) {
    override def toString: String =
      s"Composite(${elements.map(_.value).mkString(", ")})"
  }

  case class Segment(tag: String,
                     composites: List[Composite]) {
    override def toString: String =
      s"Segment($tag, ${composites.mkString(", ")})"
  }

  case class Message(header: Segment,
                     trailer: Segment,
                     segments: List[Segment]) {
    override def toString: String = {
      s"""Message(
         |  header = $header,
         |  trailer = $trailer,
         |  segments = List(${segments.map("\n    " + _).mkString(",")}
         |  )
         |)""".stripMargin
    }
  }

  object Message {
    final val headerTag = "UNH"
    final val trailerTag = "UNT"
  }

  case class GroupEnvelope(header: Segment,
                           trailer: Segment)

  case class Group(envelope: Option[GroupEnvelope],
                   messages: List[Message]) {
    override def toString: String = {
      s"""Group(
         |  envelope = $envelope,
         |  messages = List(${messages.map(_.toString.split('\n').map("\n    " + _).mkString).mkString(",")}
         |  )
         |)""".stripMargin
    }
  }

  object Group {
    final val headerTag = "UNG"
    final val trailerTag = "UNE"
  }

  case class Envelope(serviceStringAdvice: ServiceStringAdvice,
                      header: Segment,
                      trailer: Segment,
                      groups: List[Group]) {
    override def toString: String = {
      s"""Envelope(
         |  serviceStringAdvice = $serviceStringAdvice,
         |  header = $header,
         |  trailer = $trailer,
         |  groups = List(${groups.map(_.toString.split('\n').map("\n    " + _).mkString).mkString(",")}
         |  )
         |)""".stripMargin
    }
  }

  object Envelope {
    final val headerTag = "UNB"
    final val trailerTag = "UNZ"
  }

  private case class Parser(serviceStringAdvice: ServiceStringAdvice) {

    import Parser._

    private val componentSeparator = serviceStringAdvice.componentSeparatorChar.toString
    private val elementSeparator = serviceStringAdvice.elementSeparatorChar.toString
    private val release = serviceStringAdvice.releaseChar.toString
    private val segmentTerminator = serviceStringAdvice.segmentTerminatorChar.toString

    def elementReservedChar[_: P]: P[Unit] = P {
      CharPred((componentSeparator + elementSeparator + segmentTerminator).contains(_))
    }

    def elementChar[_: P]: P[String] = P {
      release ~ elementReservedChar.! |
        !elementReservedChar ~ AnyChar.!
    }

    def elementString[_: P]: P[String] = P {
      elementChar.rep.map(e => e.mkString)
    }

    def composite[_: P]: P[Composite] = P {
      elementString.rep(sep = componentSeparator).map { elements =>
        Composite(elements.map(Element).toList)
      }
    }

    def segmentReservedTags[_: P]: P[Unit] = P {
      StringIn(
        ServiceStringAdvice.tag,
        Message.headerTag,
        Message.trailerTag,
        Group.headerTag,
        Group.trailerTag,
        Envelope.headerTag,
        Envelope.trailerTag
      )
    }

    def segmentTag[_: P]: P[String] = P {
      !segmentReservedTags ~ elementString
    }

    def segment[_: P](tag: => P[String] = null): P[Segment] = P {
      (Option(tag).getOrElse(segmentTag) ~ (elementSeparator ~/ composite).rep).map {
        case (tag, composites) =>
          Segment(tag, composites.toList)
      } ~ segmentTerminator ~ s.rep
    }

    def message[_: P]: P[Message] = P {
      (segment(Message.headerTag.!) ~/
        segment().rep ~
        segment(Message.trailerTag.!)).map {
        case (header, messages, trailer) =>
          Message(header, trailer, messages.toList)
      }
    }

    def group[_: P]: P[Group] = P {
      (segment(Group.headerTag.!) ~/
        message.rep ~
        segment(Group.trailerTag.!)).map {
        case (header, messages, trailer) =>
          Group(Some(GroupEnvelope(header, trailer)), messages.toList)
      }
    }

    def envelope[_: P]: P[Envelope] = P {
      (segment(Envelope.headerTag.!) ~/
        (group | message.map(message => Group(None, List(message)))).rep ~
        segment(Envelope.trailerTag.!)).map {
        case (header, groups, trailer) =>
          Envelope(serviceStringAdvice, header, trailer, groups.toList)
      }
    }
  }

  private object Parser {
    def s[_: P]: P[Unit] = CharPred(_.isWhitespace)

    def serviceStringAdvice[_: P]: P[ServiceStringAdvice] = P {
      ServiceStringAdvice.tag ~/ (AnyChar.rep(exactly = 4).! ~ " " ~ AnyChar.!).map { una =>
        val List(
        componentSeparator,
        elementSeparator,
        decimalMark,
        releaseCharacter,
        segmentTerminator
        ) = (una._1 + una._2).toList

        ServiceStringAdvice(
          componentSeparatorChar = componentSeparator,
          elementSeparatorChar = elementSeparator,
          decimalMarkChar = decimalMark,
          releaseChar = releaseCharacter,
          segmentTerminatorChar = segmentTerminator
        )
      } ~ s.rep
    }

    def parser[_: P]: P[Envelope] = P {
      Start ~
        (serviceStringAdvice ~/ "").?
          .map(_.getOrElse(ServiceStringAdvice.default))
          .flatMap(Parser(_).envelope) ~
        End
    }
  }

  def fromString(string: String): Envelope = {
    parse[Envelope](string, Parser.parser(_)).get.value
  }
}
