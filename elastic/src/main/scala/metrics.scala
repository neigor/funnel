//: ----------------------------------------------------------------------------
//: Copyright (C) 2015 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
package funnel
package elastic

import instruments._

object metrics {
  val NonHttpErrors    = counter("elastic/errors/other")
  val HttpResponse5xx  = counter("elastic/http/5xx")
  val HttpResponse4xx  = counter("elastic/http/4xx")
  val HttpResponse2xx  = lapTimer("elastic/http/2xx")
  val BufferDropped    = counter("elastic/queue/dropped")
  val BufferUsed       = numericGauge("elastic/buffer/pending", 0)
}
