/*
 * Copyright (c) 2015-2018 Rocket Partners, LLC
 * https://github.com/inversion-api
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.inversion.cloud.action.security;

import java.util.HashSet;
import java.util.Set;

import io.inversion.cloud.model.Action;
import io.inversion.cloud.model.Request;
import io.inversion.cloud.model.Response;
import io.inversion.cloud.utils.Utils;

public class RemoveQueryParamAction extends Action<SetQueryParamAction>
{
   protected Set<String> params = new HashSet();

   public void run(Request req, Response res) throws Exception
   {
      for (String removedParam : params)
      {
         for (String param : req.getParams().keySet())
         {
            if (Utils.containsToken(removedParam, param))
            {
               req.removeParam(param);
            }
         }
      }
   }

   public RemoveQueryParamAction withParam(String name, String value)
   {
      params.add(name);
      return this;
   }

}
