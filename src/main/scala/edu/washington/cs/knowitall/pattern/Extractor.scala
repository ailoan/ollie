package edu.washington.cs.knowitall
package pattern

import java.io.{ PrintWriter, File }

import scala.Option.option2Iterable
import scala.collection.JavaConversions.{ iterableAsScalaIterable, asScalaBuffer }
import scala.collection.Set
import scala.collection.SortedSet
import scala.io.Source

import org.slf4j.LoggerFactory

import edu.washington.cs.knowitall.collection.immutable.Interval
import edu.washington.cs.knowitall.common.Resource.using
import edu.washington.cs.knowitall.extractor.ReVerbExtractor
import edu.washington.cs.knowitall.nlp.OpenNlpSentenceChunker
import edu.washington.cs.knowitall.normalization.RelationString
import edu.washington.cs.knowitall.pattern.extract.{ Postprocess, PatternExtractorType }
import edu.washington.cs.knowitall.pattern.lda.Distributions
import edu.washington.cs.knowitall.tool.parse.graph.DirectedEdge
import edu.washington.cs.knowitall.tool.parse.graph.Graph
import edu.washington.cs.knowitall.tool.parse.pattern.Match

import Extractor.{ logger, expandRelation, expandArgument, VALID_ARG_POSTAG }
import extract.{ TemplateExtractor, SpecificExtractor, PatternExtractor, LdaExtractor, GeneralExtractor, Extraction, DetailedExtraction }
import scopt.OptionParser
import tool.parse.graph.{ Direction, DependencyNode, DependencyGraph }
import tool.parse.pattern.DependencyPattern
import tool.stem.MorphaStemmer

object Extractor {
  val LEMMA_BLACKLIST = Set("for", "in", "than", "up", "as", "to", "at", "on", "by", "with", "from", "be", "like", "of")
  val VALID_ARG_POSTAG = Set("NN", "NNS", "NNP", "NNPS", "JJ", "JJS", "CD", "PRP")
  val logger = LoggerFactory.getLogger(this.getClass)
  
  implicit def implicitBuildExtraction = buildExtraction(true) _
  implicit def implicitValidMatch = validMatch(true) _
  
  def buildExtraction(expand: Boolean)(graph: DependencyGraph, m: Match[DependencyNode], ex: PatternExtractor): Option[DetailedExtraction] = {
    val groups = m.nodeGroups

    val rel = groups.get("rel").map(_.node) getOrElse (throw new IllegalArgumentException("no rel: " + m))
    val arg1 = groups.get("arg1").map(_.node) getOrElse (throw new IllegalArgumentException("no arg1: " + m))
    val arg2 = groups.get("arg2").map(_.node) getOrElse (throw new IllegalArgumentException("no arg2: " + m))

    val expandedArg1 = if (expand) expandArgument(graph, arg1, Set(rel)) else SortedSet(arg1)
    val expandedArg2 = if (expand) expandArgument(graph, arg2, Set(rel)) else SortedSet(arg2)
    val (expandedRelNodes, expandedRelText) = if (expand) expandRelation(graph, rel, Set(arg1, arg2)) else (SortedSet(rel), rel.text)
    if (Interval.span(expandedArg1.map(_.indices)(scala.collection.breakOut)) intersects Interval.span(expandedArg2.map(_.indices)(scala.collection.breakOut))) {
      logger.info("invalid: arguments overlap: " + DetailedExtraction.nodesToString(expandedArg1) + ", " + DetailedExtraction.nodesToString(expandedArg2))
      None
    }
    else Some(new DetailedExtraction(ex, m, expandedArg1, expandedRelNodes, expandedRelText, expandedArg2))
  }

  def validMatch(restrictArguments: Boolean)(graph: Graph[DependencyNode])(m: Match[DependencyNode]) = {
    !restrictArguments || (VALID_ARG_POSTAG.contains(m.nodeGroups("arg1").node.postag) && VALID_ARG_POSTAG.contains(m.nodeGroups("arg2").node.postag))
  }

  def neighborsUntil(graph: DependencyGraph, node: DependencyNode, inferiors: List[DependencyNode], until: Set[DependencyNode]): SortedSet[DependencyNode] = {
    val lefts = inferiors.takeWhile(_ != node).reverse
    val rights = inferiors.dropWhile(_ != node).drop(1)

    val indices = Interval.span(node.indices :: lefts.takeWhile(!until(_)).map(_.indices) ++ rights.takeWhile(!until(_)).map(_.indices))

    // use the original dependencies nodes in case some information
    // was lost.  For example, of is collapsed into the edge prep_of
    graph.nodes.filter(node => node.indices.max >= indices.min && node.indices.max <= indices.max)
  }

  def expandAdjacent(graph: DependencyGraph, node: DependencyNode, until: Set[DependencyNode], labels: Set[String]) = {
    def takeAdjacent(interval: Interval, nodes: List[DependencyNode], pool: List[DependencyNode]): List[DependencyNode] = pool match {
      // can we add the top node?
      case head :: tail if (head.indices borders interval) && !until.contains(head) =>
        takeAdjacent(interval union head.indices, head :: nodes, tail)
      // otherwise abort
      case _ => nodes
    }

    // it might be possible to simply have an adjacency restriction
    // in this condition
    def cond(e: Graph.Edge[DependencyNode]) =
      labels.contains(e.label)
    val inferiors = graph.graph.inferiors(node, cond).toList.sortBy(_.indices)

    // split into nodes left and right of node
    val lefts = inferiors.takeWhile(_ != node).reverse
    val rights = inferiors.dropWhile(_ != node).drop(1)

    // take adjacent nodes from each list
    val withLefts = takeAdjacent(node.indices, List(node), lefts)
    val expanded = takeAdjacent(node.indices, withLefts, rights)

    SortedSet(expanded: _*)
  }

  def expand(graph: DependencyGraph, node: DependencyNode, until: Set[DependencyNode], labels: Set[String]) = {
    // don't restrict to adjacent (by interval) because prep_of, etc.
    // remove some nodes that we want to expand across.  In the end,
    // we get the span over the inferiors.  Do go beneath until
    // nodes because we need them for neighborsUntil.
    def cond(e: Graph.Edge[DependencyNode]) =
      labels.contains(e.label)
    val inferiors = graph.graph.inferiors(node, cond).toList.sortBy(_.indices)
    neighborsUntil(graph, node, inferiors, until)
  }

  def augment(graph: DependencyGraph, node: DependencyNode, without: Set[DependencyNode], pred: Graph.Edge[DependencyNode] => Boolean): List[SortedSet[DependencyNode]] = {
    // don't restrict to adjacent (by interval) because prep_of, etc.
    // remove some nodes that we want to expand across.  In the end,
    // we get the span over the inferiors.
    graph.graph.successors(node, pred).map(SortedSet[DependencyNode]() ++ graph.graph.inferiors(_)).toList
  }

  /**
    *  Return the components next to the node.
    *  @param  node  components will be found adjacent to this node
    *  @param  labels  components may be connected by edges with any of these labels
    *  @param  without  components may not include any of these nodes
    */
  def components(graph: DependencyGraph, node: DependencyNode, labels: Set[String], without: Set[DependencyNode], nested: Boolean) = {
    // nodes across an allowed label to a subcomponent
    val across = graph.graph.neighbors(node, (dedge: DirectedEdge[_]) => dedge.dir match {
      case Direction.Down if labels.contains(dedge.edge.label) => true
      case _ => false
    })

    val components = across.flatMap { start =>
      // get inferiors without passing back to node
      val inferiors = graph.graph.inferiors(start,
        (e: Graph.Edge[DependencyNode]) =>
          // make sure we don't cycle out of the component
          e.dest != node &&
            // make sure we don't descend into another component
            // i.e. "John M. Synge who came to us with his play direct
            // from the Aran Islands , where the material for most of
            // his later works was gathered" if nested is false
            (nested || !labels.contains(e.label)))

      // make sure none of the without nodes are in the component
      if (without.forall(!inferiors.contains(_))) {
        val span = Interval.span(inferiors.map(_.indices).toSeq)
        Some(graph.nodes.filter(node => span.superset(node.indices)))
      }
      else None
    }

    components.flatten.toList
  }

  def expandArgument(graph: DependencyGraph, node: DependencyNode, until: Set[DependencyNode]): SortedSet[DependencyNode] = {
    val labels =
      Set("det", "prep_of", "amod", "num", "nn", "poss", "quantmod", "neg")

    def expandNode(node: DependencyNode) = {
      val expansion = expand(graph, node, until, labels)
      if (expansion.exists(_.isProperNoun)) expansion
      else expansion ++ components(graph, node, Set("rcmod", "infmod", "partmod", "ref", "prepc_of"), until, false)
    }

    // expand over any conjunction/disjunction edges
    val nodes = graph.graph.connected(node, (dedge: DirectedEdge[_]) =>
      dedge.edge.label == "conj_and" || dedge.edge.label == "conj_or")

    if (nodes.size == 1) {
      // there are no conjunctive edges
      expandNode(node)
    }
    else {
      val flat = nodes.map(expandNode).flatten
      val span = Interval.span(flat.map(_.indices).toSeq)
      // take the nodes that cover all the nodes found
      graph.nodes.filter(node => span.superset(node.indices))
    }
  }

  def expandRelation(graph: DependencyGraph, node: DependencyNode, until: Set[DependencyNode]): (SortedSet[DependencyNode], String) = {
    val nounLabels =
      Set("det", "amod", "num", "nn", "poss", "quantmod", "neg")

    // count the adjacent dobj edges.  We will only expand across
    // dobj components if there is exactly one adjacent dobj edge.
    // This edge may already be used, but in that case we won't 
    // expand over it because of the until set.
    val dobjCount = graph.graph.edges(node).count(_.label == "dobj")
    val iobjCount = graph.graph.edges(node).count(_.label == "iobj")

    var attachLabels = Set[String]()
    if (dobjCount == 1) attachLabels += "dobj"
    if (iobjCount == 1) attachLabels += "iobj"

    def pred(edge: Graph.Edge[DependencyNode]) = edge.label == "advmod" && edge.dest.postag == "RB" ||
      edge.label == "aux" || edge.label == "cop" || edge.label == "auxpass" || edge.label == "prt"

    // how many dobj edges are there
    val expansion = expand(graph, node, until, nounLabels) ::
      (augment(graph, node, until, pred) :+
        SortedSet[DependencyNode]() ++ components(graph, node, attachLabels, until, true)).filter(!_.isEmpty)

    val sorted = expansion.sortBy(nodes => Interval.span(nodes.map(_.indices)))

    // perform a more complicated node->text transformation
    val texts = sorted.map(DetailedExtraction.nodesToString(_))
    (expansion.reduce(_ ++ _), texts.mkString(" "))
  }

  def loadLdaExtractorsFromDistributions(dist: Distributions): List[LdaExtractor] = {
    (for (p <- dist.patternCodes) yield {
      new LdaExtractor(DependencyPattern.deserialize(dist.patternDecoding(p)), dist)
    }).toList
  }

  def loadGeneralExtractorsFromDistributions(dist: Distributions): List[GeneralExtractor] = {
    (for (p <- dist.patternCodes) yield {
      new GeneralExtractor(DependencyPattern.deserialize(dist.patternDecoding(p)), dist)
    }).toList
  }

  def loadSpecificExtractorsFromDistributions(dist: Distributions): List[GeneralExtractor] = {
    (for (
      p <- dist.patternCodes;
      val pattern = DependencyPattern.deserialize(dist.patternDecoding(p));
      r <- dist.relationsForPattern(p)
    ) yield {
      new SpecificExtractor(dist.relationDecoding(r),
        pattern,
        dist)
    }).toList
  }

  def loadExtractors(extractorType: PatternExtractorType, patternFile: File): List[PatternExtractor] =
    loadExtractors(extractorType, None, Some(patternFile))

  def loadExtractors(extractorType: PatternExtractorType,
    distributions: Option[Distributions],
    patternFile: Option[File]): List[PatternExtractor] =
    {
      logger.info("reading patterns")

      // sort by inverse count so frequent patterns appear first 
      ((extractorType, distributions) match {
        case (LdaExtractor, Some(distributions)) => loadLdaExtractorsFromDistributions(distributions)
        case (GeneralExtractor, Some(distributions)) => loadGeneralExtractorsFromDistributions(distributions)
        case (TemplateExtractor, None) =>
          TemplateExtractor.fromFile(
            patternFile.getOrElse(
              throw new IllegalArgumentException("pattern template file (--patterns) required for the template extractor.")))
        case (SpecificExtractor, Some(distributions)) => loadSpecificExtractorsFromDistributions(distributions)
        case (GeneralExtractor, None) =>
          GeneralExtractor.fromFile(
            patternFile.getOrElse(
              throw new IllegalArgumentException("pattern file (--patterns) required for the general extractor.")))
        case _ => throw new IllegalArgumentException("invalid parameters")
      }).toList
    }

  private case class Result(conf: Double, extr: DetailedExtraction, text: String) extends Ordered[Result] {
    override def toString = text

    override def compare(that: Result) = {
      val conf = this.conf.compare(that.conf)

      if (conf == 0) (this.extr.toString + text).compareTo(that.extr.toString + text)
      else conf
    }
  }

  def main(args: Array[String]) {
    val parser = new OptionParser("applypat") {
      var patternFile: Option[File] = None
      var ldaDirectoryPath: Option[String] = None
      var outputFile: Option[File] = None

      var sentenceFilePath: String = null
      var extractorType: PatternExtractorType = null

      var confidenceThreshold = 0.0;

      var showReverb: Boolean = false
      var duplicates: Boolean = false
      var expandArguments: Boolean = false
      var showAll: Boolean = false
      var verbose: Boolean = false
      var collapseVB: Boolean = false

      opt(Some("p"), "patterns", "<file>", "pattern file", { v: String => patternFile = Option(new File(v)) })
      opt(None, "lda", "<directory>", "lda directory", { v: String => ldaDirectoryPath = Option(v) })
      doubleOpt(Some("t"), "threshold", "<threshold>", "confident threshold for shown extractions", { t: Double => confidenceThreshold = t })
      opt("o", "output", "output file (otherwise stdout)", { path => outputFile = Some(new File(path)) })

      opt("d", "duplicates", "keep duplicate extractions", { duplicates = true })
      opt("x", "expand-arguments", "expand extraction arguments", { expandArguments = true })
      opt("r", "reverb", "show which extractions are reverb extractions", { showReverb = true })
      opt("collapse-vb", "collapse 'VB.*' to 'VB' in the graph", { collapseVB = true })

      opt("a", "all", "don't restrict extractions to are noun or adjective arguments", { showAll = true })
      opt("v", "verbose", "", { verbose = true })

      arg("type", "type of extractor", { v: String => extractorType = PatternExtractorType(v) })
      arg("sentences", "sentence file", { v: String => sentenceFilePath = v })

      def configuration = new Configuration(
        confidenceThreshold = this.confidenceThreshold,
        expandExtraction = this.expandArguments,
        simplifyVBPostags = this.collapseVB,
        keepDuplicates = this.duplicates)
    }

    if (parser.parse(args)) {
      logger.info("args: " + args.mkString(" "))

      // optionally load the distributions
      val distributions = parser.ldaDirectoryPath.map {
        logger.info("loading distributions")
        Distributions.fromDirectory(_)
      }

      // load the individual extractors
      val extractors = loadExtractors(parser.extractorType, distributions, parser.patternFile)

      // create a standalone extractor
      val configuration = parser.configuration
      val extractor = new Extractor(extractors, configuration)

      // create items necessary for reverb
      val chunker = if (parser.showReverb) Some(new OpenNlpSentenceChunker) else None
      val reverb = if (parser.showReverb) Some(new ReVerbExtractor) else None

      // apply reverb to the sentence and return the extractions
      def reverbExtract(sentence: String) = {
        import scala.collection.JavaConversions._

        reverb.map { reverb =>
          val chunked = chunker.get.chunkSentence(sentence)
          val extractions = reverb.extract(chunked)
          extractions.map { extr =>
            val rs = new RelationString(extr.getRelation.getText, extr.getRelation.getTokens.map(MorphaStemmer.instance.lemmatize(_)).mkString(" "), extr.getRelation.getPosTags.mkString(" "))
            rs.correctNormalization()

            new Extraction(extr.getArgument1.getText, extr.getRelation.getText, Some(rs.getNormPred.split(" ").toSet -- Extractor.LEMMA_BLACKLIST), extr.getArgument2.getText)
          }
        }.getOrElse(Nil)
      }

      logger.info("performing extractions")
      using(parser.outputFile.map(new PrintWriter(_)).getOrElse(new PrintWriter(System.out))) { writer =>
        using(Source.fromFile(parser.sentenceFilePath)) { sentenceSource =>
          for (line <- sentenceSource.getLines) {
            val parts = line.split("\t")
            require(parts.length <= 2, "each line in sentence file must have no more than two columns: " + line)

            try {
              val pickled = parts.last
              val dgraph = DependencyGraph.deserialize(pickled)
              val text = if (parts.length > 1) parts(0) else dgraph.text
              logger.debug("text: " + text)
              logger.debug("graph: " + dgraph.serialize)

              if (parser.verbose) {
                writer.println("text: " + text)
                writer.println("deps: " + dgraph.serialize)
              }

              val reverbExtractions = reverbExtract(text)
              if (parser.showReverb) {
                if (parser.verbose) writer.println("reverb: " + reverbExtractions.mkString("[", "; ", "]"))
                logger.debug("reverb: " + reverbExtractions.mkString("[", "; ", "]"))
              }

              val results = for ((conf, extr) <- extractor.extract(dgraph)) yield {
                val reverbMatches = reverbExtractions.find(_.softMatch(extr))
                logger.debug("reverb match: " + reverbMatches.toString)
                val extra = reverbMatches.map("\treverb:" + _.toString)

                val resultText =
                  if (parser.verbose) "extraction: " + ("%1.6f" format conf) + " " + extr.toString + " with (" + extr.extractor.pattern.toString + ")" + reverbMatches.map(" compared to " + _).getOrElse("")
                  else ("%1.6f" format conf) + "\t" + extr + "\t" + extr.extractor.pattern + "\t" + ("" /: text)((_, s) => s + "\t") + pickled + extra.getOrElse("")
                Result(conf, extr, resultText)
              }

              Postprocess.recombine(results.map(_.extr).toSet) foreach println

              if (parser.verbose) writer.println()
            }
            catch {
              case e: DependencyGraph.SerializationException => logger.error("could not deserialize graph.", e)
            }
          }
        }
      }
    }
  }

  class Configuration(
    val simplifyVBPostags: Boolean = false,
    val simplifyPostags: Boolean = true,
    val confidenceThreshold: Double = 0.0,
    val expandExtraction: Boolean = true,
    val restrictArguments: Boolean = true,
    val keepDuplicates: Boolean = false)
}

class Extractor(extractors: Seq[PatternExtractor], configuration: Extractor.Configuration) {
  import Extractor._
  
  def this(extractors: Seq[PatternExtractor]) = 
    this(extractors, new Extractor.Configuration())

  def simplifyGraph(dgraph: DependencyGraph) = {
    var graph = dgraph

    if (configuration.simplifyPostags) {
      graph = dgraph.simplifyPostags
    }

    if (configuration.simplifyVBPostags) {
      graph = dgraph.simplifyVBPostags
    }

    graph
  }

  def extract(dg: DependencyGraph) = {
    val dgraph = simplifyGraph(dg)

    /**
      * Check if the PatternExtractor gives a deterministic confidence.
      * If so, make sure it is above the threshold.
      */
    def confidenceOverThreshold(extractor: PatternExtractor, threshold: Double) = {
      extractor.confidence match {
        // there is an independent confidence so do the check
        case Some(conf) => conf >= configuration.confidenceThreshold
        // there is no independent confidence, so we need to continue
        // and compare the dependent confidence
        case None => true
      }
    }

    /**
      * Quick checks to see if an extraction is possible.  This
      * is an optimization, so the checks should be considerably
      * faster than running the extractors.
      */
    def possibleExtraction(extractor: PatternExtractor, dgraph: DependencyGraph) = {
      extractor.pattern.edgeMatchers.forall { matcher =>
        dgraph.dependencies.exists(matcher.canMatch(_))
      }
    }

    // implicit methods on individual extractors
    val build = buildExtraction(configuration.expandExtraction) _
    val valid = validMatch(configuration.restrictArguments) _

    val extrs = for {
      extractor <- extractors;
      // todo: organize patterns by a reverse-lookup on edges

      // optimizations
      if (confidenceOverThreshold(extractor, configuration.confidenceThreshold));
      if (possibleExtraction(extractor, dgraph));

      // extraction
      extr <- extractor.extract(dgraph)(build, valid)
      val conf = extractor.confidence(extr);
      if conf >= configuration.confidenceThreshold
    } yield {
      (conf, extr)
    }

    val reduced = if (configuration.keepDuplicates) {
      extrs
    }
    else {
      (for (group <- extrs.groupBy(_._2)) yield (group._2.maxBy(_._1))).toSeq
    }

    reduced.sortBy { case (conf, extr) => (-conf, extr.toString) }
  }
}