package acumen.interpreters.enclosure

import acumen.Tag

/**
  * A non oriented graph containing tags as vertices
  * Two vertices are connected if the intervals corresponding to the tags are overlapping
  * The goal is to find sets of tags which forms disjointed sets of values
  * It helps to achieve the probability bounding calculation more accurately
  * The weight of an edge is a heuristic supposed to reflect how bad it could be to cut at this place
  * The value of a vertex is a heuristic supposed to reflect how bad it could be to keep this vertex in a group
  * @param vertices the vertices of the graph
  * @param edges the edges of the graph
  * @param tags the tags involved in the graph
  */
abstract class TagsGraph (private val vertices: Array[Double], private val edges: Array[Array[Option[Double]]], private val tags: Array[Tag]) {
  require(edges.isEmpty || edges(0).length == edges.length, "The matricial representation of a graph must be squared.")
  require(edges.length == tags.length, "The number of tags must be equal to the width of the matrix.")

  /**
    * Cuts the graph into two subgraphs whose tags are disconnected. I.e. the union of the subintervals corresponding
    * to the tags in each subgraph or disjoined.
    * @return the two disjoined graphs
    */
  def cut : (TagsGraph, TagsGraph)
  /*
    Example of trivial cut algorithm:
    The tags are sorted with respect of the intervals the correspond to.
    1) Take the first tag to build the first new graph.
    2) Find the first tag whose corresponding interval do not overlap with the first one
    3) Put it and all the next one into the second new graph
   */

  def degree(i: Int) = edges(i).count(_.nonEmpty)
  def getTags = tags.toList
  def size = tags.length
  def isEmpty = tags.isEmpty
}

object TagsGraph {
  /**
    * Produces the parameter for any graph from a map of tags to intervals
    * @param values map of tags to intervals used to build the graph parameters
    * @return the list of vertices, the list of edges and the list of tags
    */
  def applyHelper(values: Map[Tag, Interval]): (Array[Double], Array[Array[Option[Double]]], Array[Tag]) = {
    val tags = values.keys.toArray.sortWith{case (t1, t2) =>
      values(t1).loDouble < values(t2).loDouble ||
        values(t1).loDouble == values(t2).loDouble && values(t1).hiDouble <= values(t2).hiDouble
    }
    //The weight of an edge is equal to the ratio between the size of the overlapping area
    //and the size of the union of the two corresponding intervals
    val edges = Array.ofDim[Option[Double]](tags.length, tags.length)
    for(i <- tags.indices; j <- (i + 1) until tags.length) {
      val (i1, i2) = (values(tags(i)), values(tags(j)))
      edges(i)(j) = if(i1 eq i2) Some(1.0)
                    else
                      i1.intersect(i2) match {
                        case Some(x) => Some((x.hiDouble - x.loDouble) / (i2.hiDouble - i1.loDouble))
                        case None => None
                      }
      edges(j)(i) = edges(i)(j)
      edges(i)(i) = None
    }
    //The value of a vertex is the ratio between the size of the corresponding interval and the total range
    val range = (values.values reduce (_/\_)).width.loDouble
    val vertices = Array.ofDim[Double](tags.length)
    for (i <- vertices.indices) vertices(i) = values(tags(i)).width.loDouble / range
    edges(tags.length-1)(tags.length-1) = None
    (vertices, edges, tags)
  }
}

class TagsGraphNaive(private val vertices: Array[Double], private val edges: Array[Array[Option[Double]]], private val tags: Array[Tag])
  extends TagsGraph(vertices, edges, tags) {
  override def cut: (TagsGraphNaive, TagsGraphNaive) = {
    // Trivial split for first experiments
    if (isEmpty)
      (TagsGraphNaive.empty, TagsGraphNaive.empty)
    else {
      //Try to find the first vertex <= to the first condition. If not, the second, etc.
      val conditions = Array(0.1, 0.2, 0.5, 0.75, 1)
      var condOK = false
      var condition = 0
      var vertex: Int = -1
      while (!condOK && condition <= 4) {
        vertex += 1
        //The vertex must have a low value but it is penalized if it not among the first
        if (vertices(vertex) * (vertex * 0.2 + 1) <= conditions(condition)) condOK = true
        if (vertex == vertices.length - 1 && !condOK) {
          vertex = 0; condition += 1
        }
      }
      //remove all the connected tags
      val firstNonConnected = {
        var i = vertex + 1
        while (i < edges(0).length && edges(0)(i).nonEmpty) i += 1
        i
      }
      (TagsGraphNaive(Array(vertices(vertex)), Array(Array(edges(0)(0))), Array(tags(0))),
       TagsGraphNaive(vertices.slice(firstNonConnected, tags.length),
          edges.slice(firstNonConnected, tags.length) map (_.slice(firstNonConnected, tags.length)),
          tags.slice(firstNonConnected, tags.length)))
    }
  }
}

object TagsGraphNaive {
  def apply(vertices: Array[Double], edges: Array[Array[Option[Double]]], tags: Array[Tag]) =
    new TagsGraphNaive(vertices, edges, tags)
  def empty = TagsGraphNaive(Array.empty, Array.empty, Array.empty)
  def apply(values: Map[Tag, Interval]): TagsGraph = {
    val (vertices, edges, tags) = TagsGraph.applyHelper(values)
    new TagsGraphNaive(vertices, edges, tags)
  }
}