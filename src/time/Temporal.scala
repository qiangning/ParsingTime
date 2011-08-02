package time

import org.joda.time._
import scala.collection.mutable.ArrayBuffer

//------------------------------------------------------------------------------
// TEMPORAL
//------------------------------------------------------------------------------
trait Temporal {
	def apply[E <: Temporal](offset:Int):Time=>E
	def prob(offset:Int):Double
	def exists(offset:Int):Time=>Boolean = (ground:Time) => (offset == 0)


	final def forwardIterator[E <: Temporal](ground:Time):BufferedIterator[E] = {
		var rightPointer:Int = 0;
		new Iterator[E]{
			def hasNext:Boolean = Temporal.this.exists(rightPointer)(ground)
			def next:E = {
				val rtn = apply[E](rightPointer)(ground)
				rightPointer += 1
				rtn
			}
		}.buffered
	}
	final def backwardIterator[E <: Temporal](ground:Time):BufferedIterator[E] = {
		var leftPointer:Int = 0; //NOTE: will both hit zero
		new Iterator[E]{
			def hasNext:Boolean = Temporal.this.exists(leftPointer)(ground)
			def next:E = {
				val rtn = apply[E](leftPointer)(ground)
				leftPointer -= 1
				rtn
			}
		}.buffered
	}
	final def forwardIterable[E <: Temporal](ground:Time):Iterable[E] = {
		new Iterable[E]{ 
			def iterator:Iterator[E] = forwardIterator(ground)
		}
	}
	final def backwardIterable[E <: Temporal](ground:Time):Iterable[E] = {
		new Iterable[E]{ 
			def iterator:Iterator[E] = backwardIterator(ground)
		}
	}
	final def distribution(ground:Time):Iterable[(Temporal,Double,Int)] = {
		var leftPointer = -1;
		var rightPointer = 0;
		new Iterable[(Temporal,Double,Int)]{
			def iterator:Iterator[(Temporal,Double,Int)]
					= new Iterator[(Temporal,Double,Int)]{
				def hasNext:Boolean
					= Temporal.this.exists(leftPointer)(ground) || 
					  Temporal.this.exists(rightPointer)(ground)
				def next:(Temporal,Double,Int) = {
					val pLeft = prob(leftPointer)
					val pRight = prob(rightPointer)
					if(Temporal.this.exists(leftPointer)(ground) && pLeft > pRight){
						val rtn:Temporal = apply(leftPointer)(ground)
						leftPointer -= 1
						(rtn,pLeft,leftPointer)
					} else if(Temporal.this.exists(rightPointer)(ground)){
						val rtn:Temporal = apply(rightPointer)(ground)
						rightPointer += 1
						(rtn,pRight,rightPointer)
					} else {
						throw new NoSuchElementException()
					}
				}
			}
		}
	}
	final def apply(ground:Time):Temporal = {
		if(this.exists(0)(ground)) {
			apply(0)(ground)
		} else {
			new NoTime
		}
	}

	final def all(ground:Time):Array[Temporal] = {
		distribution(ground).map( _._1 ).toArray
	}
}

object Temporal {
	def join[A](array:Array[A], str:String) = {
		if(array.length == 0){
			""
		} else {
			val sb:StringBuilder = new StringBuilder
			array.foreach( (a:A) => {
				sb.append(a).append(str)
			})
			sb.substring(0, sb.length - str.length)
		}
	}

	var reader:scala.tools.nsc.interpreter.JLineReader = null
	var interpreter:scala.tools.nsc.interpreter.IMain = null
	def interactive = {
		import scala.tools.nsc.interpreter.{IMain,JLineReader,JLineCompletion}
		import scala.tools.nsc.Settings
		//--Create Interpreter
		println("Loading interpreter...")
		if(reader == null){
			//(objects)
			val settings = new Settings
			settings.usejavacp.value = true
			interpreter = new IMain(settings)
			//(initialize)
			interpreter.interpret("import time._")
			interpreter.interpret("import time.Lex._")
			interpreter.interpret("val ground = Time(2011,4,26)")
			interpreter.interpret(
				"org.joda.time.DateTimeZone.setDefault(org.joda.time.DateTimeZone.UTC);"
			)
			reader = new JLineReader(new JLineCompletion(interpreter))
		}
		//--Loop
		var cond = true
		while(cond){
			val str = reader.readLine("scala> ")
			interpreter.interpret(str)
		}
	}

}


//------------------------------------------------------------------------------
// RANGE
//------------------------------------------------------------------------------
// ----- RANGE -----
trait Range extends Temporal{
	def >>(diff:Duration):Range //shift right
	def <<(diff:Duration):Range //shift left
	def |>(diff:Duration):Range //extend right
	def <|(diff:Duration):Range //extend left
	def >|(diff:Duration):Range //shirnk to right
	def |<(diff:Duration):Range //shrink to left

	private def composite[E <: Temporal](
			str:String,
			other:Range,
			fn:(Boolean,Iterable[E],Iterable[E])=>Iterable[(GroundedRange,Int,Int)]
				):CompositeRange = {
		var cacheBackward:(Int=>GroundedRange,Int=>(Int,Int),Int=>Boolean) = null
		var cacheForward:(Int=>GroundedRange,Int=>(Int,Int),Int=>Boolean) = null
		var cacheCond:Time = null
		def ensureCache(ground:Time) = {
			if(cacheForward == null  || ground != cacheCond){
				cacheCond = ground
				cacheForward = Range.iter2apply(fn(
						false, //forwards search
						this.forwardIterable(ground),
						other.forwardIterable(ground)
					).iterator)
				cacheBackward = 
					Range.iter2apply(fn(
						true, //backwards search
						this.backwardIterable(ground),
						other.backwardIterable(ground)
					).iterator)
			}
			(cacheForward,cacheBackward)
		}
		new CompositeRange(
			//(new apply)
			(offset:Int) => { (ground:Time) => {
				val ((applyForward,indexForward,existsForward),
				     (applyBackward,indexBackward,existsBackward)) = ensureCache(ground)
				if(offset == 0) {
					if(existsForward(0)){
						applyForward(0)
					} else {
						applyBackward(0)
					}
				} else if(offset > 0){
					applyForward(offset)
				} else {
					applyBackward(-offset)
				}
			}},
			//(new prob)
			(offset:Int) => this.prob(offset),
			//(new offset)
			(offset:Int) => { (ground:Time) => {
				val ((applyForward,indexForward,existsForward),
				     (applyBackward,indexBackward,existsBackward)) = ensureCache(ground)
				if(offset == 0) {
					val forward = existsForward(0)
					if(forward){
						forward
					} else {
						existsBackward(0)
					}
				} else if(offset > 0){
					existsForward(offset)
				} else {
					existsBackward(-offset)
				}
			}},
			List[String]("("+this + ") "+str+" (" + other+")")
		)
	}

	def cons(other:Range):Range = {
		(this, other) match {
			case (a:GroundedRange,b:GroundedRange) => 
				if(a.begin < b.end){ new GroundedRange(a.begin,b.end) }
				else { new NoTime }
			case _ => composite("cons",other,
				Range.cons(_:Boolean,
					_:Iterable[GroundedRange],_:Iterable[GroundedRange]))
		}
	}

	def ^(other:Range):Range = {
		(this, other) match {
			case (a:GroundedRange,b:GroundedRange) => new GroundedRange(
					Range.mkBegin(a.begin,b.begin),
					Range.mkEnd(a.end,b.end)
				)
			case _ => composite("^",other,
				Range.intersect(_:Boolean,
					_:Iterable[GroundedRange],_:Iterable[GroundedRange]))
		}
	}
}

// ----- COMPOSITE RANGE -----
class CompositeRange( 
			applyFn:Int=>(Time=>Range),
			probFn:Int=>Double,
			existsFn:Int=>(Time=>Boolean),
			ops:List[String]
		) extends Range {
	
	override def apply[E <: Temporal](offset:Int):Time=>E = {
		applyFn(offset) match {
			case (fn:(Time=>E)) => (ground:Time) => {
				assert(fn(ground).isInstanceOf[GroundedRange], "Composite ungrounded")
				fn(ground)
			}
			case _ => throw new IllegalArgumentException("Runtime Type Error")
		}
	}
	override def prob(offset:Int):Double = probFn(offset)
	override def exists(offset:Int):Time=>Boolean = existsFn(offset)

	override def >>(diff:Duration):Range = extend( _ >> diff, ">>" )
	override def <<(diff:Duration):Range = extend( _ << diff, "<<" )
	override def |>(diff:Duration):Range = extend( _ |> diff, "|>" )
	override def <|(diff:Duration):Range = extend( _ <| diff, "<|" )
	override def >|(diff:Duration):Range = extend( _ >| diff, ">|" )
	override def |<(diff:Duration):Range = extend( _ |< diff, "|<" )

	private def extend(fn:Range=>Range, op:String) = {
		new CompositeRange( 
			(offset:Int) => { (ground:Time) => fn( this.applyFn(offset)(ground) ) },
			(offset:Int) => this.probFn(offset),
			(offset:Int) => this.existsFn(offset),
			op :: this.ops
		)
	}
	
	override def equals(o:Any):Boolean = this == o
	override def toString:String = Temporal.join(ops.toArray, " <- ")
	override def hashCode:Int =throw new IllegalStateException("Dont hash me bro")
}

// ----- GROUNDED RANGE -----
class GroundedRange(val begin:Time,val end:Time) extends Range {
	override def apply[E <: Temporal](offset:Int):Time=>E = {
		if(offset == 0){ (t:Time) => 
			this match{ 
				case (e:E) => e
				case _ => throw new IllegalArgumentException("Runtime Type Error")
			}
		}
		else{throw new TimeException("GroundedRange given nonzero offset: "+offset)}
	}
	override def prob(offset:Int):Double = if(offset == 0){ 1.0 } else{ 0.0 }

	
	override def >>(diff:Duration):Range = new GroundedRange(begin+diff,end+diff)
	override def <<(diff:Duration):Range = new GroundedRange(begin-diff,end-diff)
	override def <|(diff:Duration):Range = new GroundedRange(begin-diff,begin)
	override def |>(diff:Duration):Range = new GroundedRange(end,end+diff)
	override def |<(diff:Duration):Range = new GroundedRange(begin,begin+diff)
	override def >|(diff:Duration):Range = new GroundedRange(end-diff,end)

	
	def norm:Duration = (begin - end)

	override def equals(o:Any):Boolean = o match {
		case (gr:GroundedRange) => 
			gr.begin.equals(this.begin) && gr.end.equals(this.end)
		case _ => false
	}
	override def toString:String = "["+begin+", "+end+")"
	override def hashCode:Int =throw new IllegalStateException("Dont hash me bro")
}


// ----- UNGROUNDED RANGE -----
class UngroundedRange(val normVal:Duration,val beginOffset:Duration
		) extends Range{
	override def apply[E <: Temporal](offset:Int) = {
		if(offset == 0){ 
			(ground:Time) =>
				new GroundedRange(ground+beginOffset,ground+beginOffset+normVal) match {
					case (e:E) => e
					case _ => throw new IllegalArgumentException("Runtime Type Error")
				}
		} else{
			throw new TimeException("UngroundedRange given nonzero offset: "+offset)
		}
	}
	override def prob(offset:Int):Double = if(offset == 0){ 1.0 } else{ 0.0 }

	override def >>(diff:Duration):Range 
		= new UngroundedRange(normVal,beginOffset+diff)

	override def <<(diff:Duration):Range 
		= new UngroundedRange(normVal,beginOffset-diff)

	override def <|(diff:Duration):Range 
		= new UngroundedRange(diff,beginOffset-diff)

	override def |>(diff:Duration):Range 
		= new UngroundedRange(diff,beginOffset+normVal)

	override def |<(diff:Duration):Range 
		= new UngroundedRange(diff,beginOffset)

	override def >|(diff:Duration):Range 
		= new UngroundedRange(diff,beginOffset+normVal-diff)
	
	def norm:Duration = normVal
	
	override def equals(o:Any):Boolean = o match {
		case (ur:UngroundedRange) => 
			ur.normVal.equals(this.normVal) && ur.beginOffset.equals(this.beginOffset)
		case _ => false
	}
	override def toString:String 
		= "[x"+{if(beginOffset.seconds==0) "" else "+"+beginOffset}+", x+"+
			(beginOffset+normVal)+")"
	override def hashCode:Int =throw new IllegalStateException("Dont hash me bro")
}



// ----- OBJECT RANGE -----
object Range {
	def apply(begin:Time,end:Time) = new GroundedRange(begin,end)
	def apply(begin:Time) = new GroundedRange(begin,begin)
	def apply(begin:DateTime,end:DateTime) 
		= new GroundedRange(Time(begin),Time(end))
	def apply(begin:DateTime) 
		= new GroundedRange(Time(begin),Time(begin))
	def apply(begin:Range,end:Range) = begin cons end
	def apply(norm:Duration) = new UngroundedRange(norm,Duration.ZERO)
	def apply(norm:Period) = new UngroundedRange(Duration(norm),Duration.ZERO)

	def iter2apply(iter:Iterator[(GroundedRange,Int,Int)]
			):(Int=>GroundedRange,Int=>(Int,Int),Int=>Boolean) = {
		val buffer = new ArrayBuffer[(GroundedRange,Int,Int)]()
		(
			(in:Int) => {
				while(in >= buffer.length && iter.hasNext){ buffer.append(iter.next) }
				if(in < buffer.length){ buffer(in)._1 } 
				else{ throw new IllegalArgumentException("Out of bounds index: " + in +
					" (has next? " + iter.hasNext + ")") }
			},
			(in:Int) => {
				while(in >= buffer.length && iter.hasNext){ buffer.append(iter.next) }
				if(in < buffer.length){ (buffer(in)._2,buffer(in)._3) } 
				else{ throw new IllegalArgumentException("Out of bounds index: " + in +
					" (has next? " + iter.hasNext + ")") }
			},
			(in:Int) => {
				while(in >= buffer.length && iter.hasNext){ buffer.append(iter.next) }
				in < buffer.length
			}
		)
	}

	def mkBegin(a:Time,b:Time) = if(a < b) b else a
	def mkEnd(a:Time,b:Time) = if(a < b) a else b

	def mknext2iterable( 
			a:Iterable[GroundedRange], b:Iterable[GroundedRange],
			mkNext:(Int,GroundedRange,BufferedIterator[GroundedRange],
			        Int,GroundedRange,BufferedIterator[GroundedRange])
							=>
							(GroundedRange,
							((GroundedRange,Int),(GroundedRange,Int)),
							(Int,Int)) ):Iterable[(GroundedRange,Int,Int)] = {
		new Iterable[(GroundedRange,Int,Int)]{
			def iterator:Iterator[(GroundedRange,Int,Int)] = {
				new Iterator[(GroundedRange,Int,Int)]{
					private val iterA = a.iterator.buffered
					private val iterB = b.iterator.buffered
					private var (theNext,((headA,iA),(headB,iB)),(indexA,indexB))
						:(GroundedRange,((GroundedRange,Int),(GroundedRange,Int)),(Int,Int))
						= if(iterA.hasNext && iterB.hasNext){
								mkNext(0,iterA.next,iterA,0,iterB.next,iterB)
							} else {
								(null,((null,0),(null,0)),(0,0))
							}
					override def hasNext:Boolean = theNext != null
					override def next:(GroundedRange,Int,Int) = {
						if(theNext == null){ throw new NoSuchElementException }
						val rtn=(theNext,indexA,indexB)
						val (n,((vA,inA),(vB,inB)),(indA,indB))
							= mkNext(iA,headA,iterA,iB,headB,iterB)
						theNext=n;headA=vA;headB=vB;iA=inA;iB=inB;indexA=indA;indexB=indB
						rtn
					}
				}
			}
		}
		
	}

	def cons(back:Boolean,a:Iterable[GroundedRange],b:Iterable[GroundedRange]
			):Iterable[(GroundedRange,Int,Int)] = {
		def mkNext(
				iA:Int, vA:GroundedRange,a:BufferedIterator[GroundedRange],
				iB:Int, vB:GroundedRange,b:BufferedIterator[GroundedRange]
				):(GroundedRange,((GroundedRange,Int),(GroundedRange,Int)),(Int,Int))={
			val nullVal = (null,((null,iA),(null,iB)),(iA,iB))
			if(vA==null || vB==null){
				//(case: an iterator is empty)
				nullVal
			} else if(vB.end < vA.begin){
				//(case: B is behind)
				if(!back && b.hasNext){ mkNext(iA,vA,a,iB+1,b.next,b) } 
				else if(back && a.hasNext){ mkNext(iA+1,a.next,a,iB,vB,b) } 
				else { nullVal }
			} else {
				//(case: overlap)
				val rtn = new GroundedRange(vA.begin,vB.end)
				//(update iterator)
				if(a.hasNext){
					//(case: A can jump again)
					(rtn,((a.next,iA+1),(vB,iB)),(iA,iB))
				} else {
					(rtn,((null,iA),(vB,iB)),(iA,iB))
				}
			}
		}
		mknext2iterable(a,b,mkNext(_,_,_,_,_,_))
	}

	def intersect(back:Boolean,a:Iterable[GroundedRange],b:Iterable[GroundedRange]
			):Iterable[(GroundedRange,Int,Int)] = {
		var aBehindDiff:Duration = Duration.INFINITE
		var bBehindDiff:Duration = Duration.INFINITE
		def mkNext(
				iA:Int, vA:GroundedRange,a:BufferedIterator[GroundedRange],
				iB:Int, vB:GroundedRange,b:BufferedIterator[GroundedRange]
				):(GroundedRange,((GroundedRange,Int),(GroundedRange,Int)),(Int,Int))={
			val nullVal = (null,((null,iA),(null,iB)),(iA,iB))
			if(vA==null || vB==null){
				//(case: an iterator is empty)
				nullVal
			} else if(!(vA.end > vB.begin)) { //note: virtual <= 
				//(case: A is before)
				//((overhead for divergence))
				bBehindDiff = Duration.INFINITE
				val lastDiff = aBehindDiff
				aBehindDiff = (vB.begin-vA.end)
				if(aBehindDiff > lastDiff){ nullVal } //case: diverging
				//((movement))
				else if(!back && a.hasNext){ mkNext(iA+1,a.next,a,iB,vB,b) } 
				else if(back && b.hasNext){ mkNext(iA,vA,a,iB+1,b.next,b) } 
				else { nullVal } //case: relevant iterator is empty
			} else if(!(vB.end > vA.begin)){ //note: virtual <=
				//(case: B is before)
				//((overhead for divergence))
				aBehindDiff = Duration.INFINITE
				val lastDiff = bBehindDiff
				bBehindDiff = vA.begin-vB.end
				if(bBehindDiff > lastDiff){ nullVal } //case: diverging
				//((movement))
				else if(!back && b.hasNext){ mkNext(iA,vA,a,iB+1,b.next,b) } 
				else if(back && a.hasNext){ mkNext(iA+1,a.next,a,iB,vB,b) } 
				else { nullVal } //case: relevant iterator is empty
			} else {
				//(case: overlap)
				val rtn = new GroundedRange(
						mkBegin(vA.begin,vB.begin),
						mkEnd(vA.end,vB.end)
					)
				//(update iterator)
				if(a.hasNext && !(a.head.begin > vB.end)){
					//(case: A can jump again)
					(rtn,((a.next,iA+1),(vB,iB)),(iA,iB))
				} else if(b.hasNext) {
					//(case: B can either jump, or we jump it anyways)
					(rtn,((vA,iA),(b.next,iB+1)),(iA,iB))
				} else {
					(rtn,((vA,iA),(null,iB)),(iA,iB))
				}
			}
		}
		//(call the bloody function)
		mknext2iterable(a,b,mkNext(_,_,_,_,_,_))
	}
}

//------------------------------------------------------------------------------
// DURATION
//------------------------------------------------------------------------------
// ----- DURATION -----
trait Duration extends Temporal {
	override def apply[E <: Temporal](offset:Int):Time=>E = {
		if(offset == 0){ (t:Time) => this match {
				case (e:E) => 
					assert(e.isInstanceOf[GroundedDuration], "Duration not grounded")
					e
				case _ => throw new IllegalArgumentException("Runtime Type Error")
			}
		}
		else{ throw new TimeException("Duration given nonzero offset: "+offset) }
	}
	override def prob(offset:Int):Double = if(offset == 0){ 1.0 } else{ 0.0 }

	def interval:GroundedDuration
	def seconds:Long

	def +(diff:Duration):Duration 
	def -(diff:Duration):Duration
	def *(n:Int):Duration
	
	def <(other:Duration) = this.seconds < other.seconds
	def >(other:Duration) = this.seconds > other.seconds
}

// ----- GROUNDED DURATION -----
class GroundedDuration(val base:ReadablePeriod) extends Duration {
	override def interval:GroundedDuration = this
	override def seconds:Long = {
		var period = base.toPeriod
		val monthContrib:Long = period.getMonths*30*24*60*60
		val yearContrib:Long = period.getYears.longValue*365*24*60*60
		period = period.withMonths(0).withYears(0)
		period.toStandardDuration.getStandardSeconds+monthContrib+yearContrib
	}

	override def +(diff:Duration):Duration
		= new GroundedDuration(this.base.toPeriod.plus(diff.interval.base))
	override def -(diff:Duration):Duration 
		= new GroundedDuration(base.toPeriod.minus(diff.interval.base))
	override def *(n:Int):Duration = {
		if(base.isInstanceOf[Seconds]){
			new GroundedDuration(base.asInstanceOf[Seconds].multipliedBy(n))
		} else if(base.isInstanceOf[Minutes]){
			new GroundedDuration(base.asInstanceOf[Minutes].multipliedBy(n))
		} else if(base.isInstanceOf[Hours]){
			new GroundedDuration(base.asInstanceOf[Hours].multipliedBy(n))
		} else if(base.isInstanceOf[Days]){
			new GroundedDuration(base.asInstanceOf[Days].multipliedBy(n))
		} else if(base.isInstanceOf[Weeks]){
			new GroundedDuration(base.asInstanceOf[Weeks].multipliedBy(n))
		} else if(base.isInstanceOf[Months]){
			new GroundedDuration(base.asInstanceOf[Months].multipliedBy(n))
		} else if(base.isInstanceOf[Years]){
			new GroundedDuration(base.asInstanceOf[Years].multipliedBy(n))
		} else {
			throw new IllegalStateException("Cannot multiply Duration")
		}
	}
	
	override def equals(o:Any):Boolean = o match {
		case (gd:GroundedDuration) => gd.base.equals(this.base)
		case _ => false
	}
	override def toString:String = this.base.toString
	override def hashCode:Int =throw new IllegalStateException("Dont hash me bro")
}

// ----- OBJECT DURATION -----
object Duration {
	def apply(p:ReadablePeriod) = new GroundedDuration(p)
	def apply(millis:Long):Duration = new GroundedDuration(new Period(millis))
	val INFINITE:Duration = new GroundedDuration( Period.years(Int.MaxValue) )
	val NEG_INFINITE:Duration = new GroundedDuration( Period.years(Int.MinValue) )
	val ZERO:Duration = new GroundedDuration( new Period(0L) )
}

//------------------------------------------------------------------------------
// SEQUENCE
//------------------------------------------------------------------------------
// ----- SEQUENCE -----
trait Sequence extends Range with Duration {
	override def exists(offset:Int) = (ground:Time) => true
}

// ----- REPEATED RANGE -----
class RepeatedRange(snapFn:Time=>Time,base:Range,interv:Duration
		) extends Sequence {
	
	override def apply[E <: Temporal](offset:Int):Time=>E = {
		var cache:Temporal = null; var cacheCond:Time = null
		(ground:Time) => {
			if(cache == null || ground != cacheCond) cache = { //cache
				//(update cache condition)
				cacheCond = ground
				//(snap beginning)
				val begin:Time = snapFn(ground+interv*offset) //interv before snap
				//(ground the time)
				base match {
					case (r:{def norm:Duration}) => new GroundedRange(begin,begin+r.norm)
					case _ => throw new TimeException("Not normable: " + base)
				}
			}
			//(return cache)
			cache match {
				case (e:E) => 
					assert(e.isInstanceOf[GroundedRange], "Range not grounded")
					e
				case _ => throw new IllegalArgumentException("Runtime Type Error")
			}
		}
	}
	override def prob(offset:Int):Double = {
		offset match {
			case 0 => 0.8
			case -1 => 0.2
			case _ => 0.0
		}
	}

	def >>(diff:Duration):Range = new RepeatedRange(snapFn, base >> diff,interv)
	def <<(diff:Duration):Range = new RepeatedRange(snapFn, base << diff,interv)
	def <|(diff:Duration):Range = new RepeatedRange(snapFn, base <| diff, interv)
	def |>(diff:Duration):Range = new RepeatedRange(snapFn, base |> diff, interv)
	def |<(diff:Duration):Range = new RepeatedRange(snapFn, base |< diff, interv)
	def >|(diff:Duration):Range = new RepeatedRange(snapFn, base >| diff, interv)
	
	def interval:GroundedDuration = interv.interval
	def seconds:Long = interv.seconds


	def +(diff:Duration):Duration = new RepeatedRange(snapFn, base, interv + diff)
	def -(diff:Duration):Duration = new RepeatedRange(snapFn, base, interv - diff)
	def *(n:Int):Duration = new RepeatedRange(snapFn, base, interv * n)
	
	override def equals(o:Any):Boolean = this == o
	override def toString:String 
		= this.base.toString + " every " + interv
	override def hashCode:Int =throw new IllegalStateException("Dont hash me bro")
}

// ----- OBJECT SEQUENCE -----
object Sequence {
	def apply(snapFn:Time=>Time,norm:Duration,interval:Duration)
		= new RepeatedRange(snapFn,Range(norm),interval)
}



//------------------------------------------------------------------------------
// TIME
//------------------------------------------------------------------------------
// ----- TIME -----
case class Time(base:DateTime) {
	def >(t:Time):Boolean = this.base.getMillis > t.base.getMillis
	def <(t:Time):Boolean = this.base.getMillis < t.base.getMillis

	def +(diff:Duration):Time = {
		val diffMillis = diff.seconds*1000
		val baseMillis = base.getMillis
		if(diffMillis > 0 && baseMillis > Long.MaxValue-diffMillis){
			//((overflow))
			new Time(new DateTime(Long.MaxValue))
		} else if(diffMillis < 0 && baseMillis < Long.MinValue-diffMillis ){
			//((underflow))
			new Time(new DateTime(Long.MinValue))
		} else {
			//((normal))
			try{
				new Time(base.plus(diff.interval.base))
			} catch {
				case (e:ArithmeticException) => 
					new Time(base.plus(diffMillis)) //catch-all
			}
		}
	}

	def -(diff:Duration):Time = {
		val diffMillis = diff.seconds*1000
		val baseMillis = base.getMillis
		if( diffMillis > 0 && baseMillis < Long.MinValue+diffMillis ){
			//(underflow)
			new Time(new DateTime(Long.MinValue))
		} else if(diffMillis < 0 && baseMillis > Long.MaxValue+diffMillis){
			//(overflow)
			new Time(new DateTime(Long.MaxValue))
		} else {
			//(normal)
			try{
				new Time(base.minus(diff.interval.base))
			} catch {
				case (e:ArithmeticException) => 
					new Time(base.minus(diffMillis)) //catch-all
			}
		}
	}

	def -(other:Time):Duration = {
		assert(this.equals(Time.DAWN_OF) || this != Time.DAWN_OF, "eq check")
		assert(this.equals(Time.END_OF) || this != Time.END_OF, "eq check")
		val tM:Long = this.base.toInstant.getMillis
		val oM:Long = other.base.toInstant.getMillis
		if(this == Time.DAWN_OF){
			//(case: subtracting from neg_infinity)
			if(other == Time.DAWN_OF){ Duration.ZERO }
			else { Duration.NEG_INFINITE }
		} else if(this == Time.END_OF){
			//(case: subtracting from pos_infinity)
			if(other == Time.END_OF){ Duration.ZERO }
			else { Duration.INFINITE }
		} else if(oM < 0 && Long.MaxValue + oM < tM){
			//(case: overflowing a Long)
			Duration.INFINITE
		} else if(oM > 0 && Long.MinValue + oM > tM){
			//(case: underflowing a Long)
			Duration.NEG_INFINITE
		} else {
			//(case: normal subtraction)
			try {
				new GroundedDuration(new Period(tM-oM))
			} catch {
				//(case: overflowed precise fields)
				case (e:ArithmeticException) => 
					new GroundedDuration(
						new Period(new Period(tM-oM,PeriodType.years),PeriodType.standard))
			}
		}
	}
	override def toString:String = this.base.toString
}

// ----- OBJECT TIME -----
object Time {
	val DAWN_OF:Time = new Time(new DateTime(Long.MinValue))
	val END_OF:Time  = new Time(new DateTime(Long.MaxValue))
	
	def apply(year:Int, month:Int, day:Int, hour:Int, min:Int, sec:Int):Time = {
		apply(new DateTime(year,month,day,hour,min,sec,0))
	}
	def apply(year:Int, month:Int, day:Int, hour:Int, min:Int):Time = {
		apply(year, month, day, hour, min, 0)
	}
	def apply(year:Int, month:Int, day:Int, hour:Int):Time = {
		apply(year, month, day, hour, 0, 0)
	}
	def apply(year:Int, month:Int, day:Int):Time =apply(year, month, day, 0, 0, 0)
	def apply(year:Int, month:Int):Time = apply(year, month, 1, 0, 0, 0)
	def apply(year:Int):Time = apply(year, 1, 1, 0, 0,0)
	
	def main(args:Array[String]):Unit = {
		Temporal.interactive
	}
}


//------------------------------------------------------------------------------
// MISC
//------------------------------------------------------------------------------
// ----- TIME EXCEPTION -----
class TimeException(s:String,e:Throwable) extends RuntimeException(s,e) {
	def this() = this("",null)
	def this(s:String) = this(s,null)
	def this(e:Throwable) = this(null,e)
}

// ----- NO TIME -----
class NoTime extends Sequence {
	override def apply[E <: Temporal](offset:Int) = (ground:Time) =>
		this match {
			case (e:E) => e
			case _ => throw new IllegalArgumentException("Runtime Type Error")
		}
	override def prob(offset:Int) = 0.0

	def interval:GroundedDuration = new GroundedDuration(Seconds.ZERO)
	def seconds:Long = 0L

	def +(diff:Duration):Duration = this
	def -(diff:Duration):Duration = this
	def *(n:Int):Duration = this
	
	def >>(diff:Duration):Range = this
	def <<(diff:Duration):Range = this
	def |>(diff:Duration):Range = this
	def <|(diff:Duration):Range = this
	def >|(diff:Duration):Range = this
	def |<(diff:Duration):Range = this

	override def toString = "NOTIME"
}

class UnkTime extends NoTime {
	override def toString = "UNKTIME"
}

class PartialTime(fn:Range=>Range) extends Temporal {
	override def apply[E <: Temporal](offset:Int):Time=>E = {
		(ground:Time) =>  {
			val resolved:Range = fn(Range(Time.DAWN_OF,Time.END_OF))
			val grounded:E = resolved(offset)(ground)
			assert(grounded.isInstanceOf[GroundedRange], "Ungrounded PartialTime")
			grounded
		}
	}
	override def prob(offset:Int):Double = if(offset == 0){ 1.0 } else{ 0.0 }
	override def exists(offset:Int):Time=>Boolean
		= fn(Range(Time.DAWN_OF,Time.END_OF)).exists(offset)
}


//------------------------------------------------------------------------------
// LEX
//------------------------------------------------------------------------------
object Lex {
	object LexUtil {
		def dow(iArg:Int):Time=>Time = (t:Time) => {
			val i:Int = if(iArg < 0) t.base.getDayOfWeek else iArg
			Time(t.base.withDayOfWeek(i).withMillisOfDay(0))
		}
		def dom(iArg:Int):Time=>Time = (t:Time) => {
			val i:Int = if(iArg < 0) t.base.getDayOfWeek else iArg
			try {
				Time(t.base.withDayOfMonth(i).withMillisOfDay(0))
			} catch { case (e:org.joda.time.IllegalFieldValueException) =>
				Time(t.base.withDayOfMonth(1).withMillisOfDay(0))+MONTH-DAY
			}
		}
		def woy(iArg:Int):Time=>Time = (t:Time) => {
			val i:Int = if(iArg < 0) t.base.getDayOfWeek else iArg
			Time(t.base.withWeekOfWeekyear(i).withDayOfWeek(1).withMillisOfDay(0))
		}
		def moy(iArg:Int):Time=>Time = (t:Time) => {
			val i:Int = if(iArg < 0) t.base.getDayOfWeek else iArg
			Time(t.base.withMonthOfYear(i).withDayOfMonth(1).withMillisOfDay(0))
		}
		def qoy(iArg:Int):Time=>Time = (t:Time) => {
			val i:Int = if(iArg < 0) t.base.getDayOfWeek else iArg
			Time(t.base.withMonthOfYear(3*(i-1)+1)
				.withDayOfMonth(1).withMillisOfDay(0))
		}
		def yoc(iArg:Int):Time=>Time = (t:Time) => {
			val i:Int = if(iArg < 0) t.base.getDayOfWeek else iArg
			val newYear = t.base.getYear - (t.base.getYear%100) + i
			try{
				Time(t.base.withYear(newYear)
					.withMonthOfYear(1).withDayOfMonth(1).withMillisOfDay(0))
			} catch { case (e:IllegalFieldValueException) => 
				if(newYear < 0){
					Time.DAWN_OF
				}else{
					Time.END_OF
				}
			}
		}
	}
	//--Durations
	val SEC:Duration = new GroundedDuration(Seconds.ONE)
	val MIN:Duration = new GroundedDuration(Minutes.ONE)
	val HOUR:Duration = new GroundedDuration(Hours.ONE)
	val DAY:Duration = new GroundedDuration(Days.ONE)
	val WEEK:Duration = new GroundedDuration(Weeks.ONE)
	val MONTH:Duration = new GroundedDuration(Months.ONE)
	val QUARTER:Duration = new GroundedDuration(Months.THREE)
	val AYEAR:Duration = new GroundedDuration(Years.ONE)
	//--Misc
	val TODAY:Range = Range(DAY)
	val REF:Range = Range(Duration.ZERO)
	val ALL_Time:Range = Range(Time.DAWN_OF,Time.END_OF)
	//--Day of Week
	private def mkDOW(i:Int) = new RepeatedRange(
		LexUtil.dow(i), 
		Range(Duration(Days.ONE)), 
		Duration(Weeks.ONE))
	val MON:Sequence = mkDOW(1)
	val TUE:Sequence = mkDOW(2)
	val WED:Sequence = mkDOW(3)
	val THU:Sequence = mkDOW(4)
	val FRI:Sequence = mkDOW(5)
	val SAT:Sequence = mkDOW(6)
	val SUN:Sequence = mkDOW(7)
	//--OTHER DurationS
	def DOW(i:Int) = new RepeatedRange(
		LexUtil.dow(i), 
		Range(Duration(Days.ONE)), 
		Duration(Weeks.ONE))
	def DOM(i:Int) = new RepeatedRange(
		LexUtil.dom(i), 
		Range(Duration(Days.ONE)), 
		Duration(Months.ONE))
	def WOY(i:Int) = new RepeatedRange(
		LexUtil.woy(i), 
		Range(Duration(Weeks.ONE)), 
		Duration(Years.ONE))
	def MOY(i:Int) = new RepeatedRange(
		LexUtil.moy(i), 
		Range(Duration(Months.ONE)), 
		Duration(Years.ONE))
	def QOY(i:Int) = new RepeatedRange(
		LexUtil.qoy(i), 
		Range(Duration(Months.THREE)), 
		Duration(Years.ONE))
	def YOC(i:Int) = new RepeatedRange(
		LexUtil.yoc(i), 
		Range(Duration(Years.ONE)), 
		Duration(Years.years(100)))
	def THEYEAR(i:Int) = Range(Time(i),Time(i+1))
	def DECADE(i:Int) = Range(Time(i*10),Time((i+1)*10))
	def CENTURY(i:Int) = Range(Time(i*100),Time((i+1)*100))
	
	//--Shifts
	val shiftLeft:(Range,Duration)=>Range = _ << _
	val shiftRight:(Range,Duration)=>Range = _ >> _
	val catLeft:(Range,Duration)=>Range = _ <| _
	val catRight:(Range,Duration)=>Range = _ |> _
	val shrinkBegin:(Range,Duration)=>Range = _ |< _
	val shrinkEnd:(Range,Duration)=>Range = _ >| _
	val intersect:(Range,Range)=>Range = _ ^ _ //TODO
	val cons:(Range,Range)=>Range = _.cons(_)

	def todaysDate:Time = Time((new DateTime).withMillisOfDay(0))
}