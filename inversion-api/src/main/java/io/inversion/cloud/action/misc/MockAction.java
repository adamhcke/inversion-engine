/*
 * Copyright (c) 2015-2019 Rocket Partners, LLC
 * https://github.com/inversion-api
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.inversion.cloud.action.misc;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;

import io.inversion.cloud.model.Action;
import io.inversion.cloud.model.Api;
import io.inversion.cloud.model.ApiException;
import io.inversion.cloud.model.Endpoint;
import io.inversion.cloud.model.JSArray;
import io.inversion.cloud.model.JSNode;
import io.inversion.cloud.model.Request;
import io.inversion.cloud.model.Response;
import io.inversion.cloud.model.SC;
import io.inversion.cloud.service.Chain;
import io.inversion.cloud.service.Engine;
import io.inversion.cloud.utils.Utils;

public class MockAction extends Action<MockAction>
{
   protected JSNode json          = null;
   protected String  jsonUrl       = null;
   protected int     statusCode    = 200;
   protected String  status        = SC.SC_200_OK;
   protected boolean cancelRequest = true;

   public MockAction()
   {

   }

   public MockAction(String name)
   {
      this(null, null, name, new JSNode("name", name));
   }

   public MockAction(String methods, String includePaths, String name)
   {
      this(methods, includePaths, name, null);
   }

   public MockAction(String methods, String includePaths, String name, JSNode json)
   {
      withMethods(methods);
      withIncludePaths(includePaths);
      withName(name);
      withJson(json);
   }

   @Override
   public void run(Engine engine, Api api, Endpoint endpoint, Chain chain, Request req, Response res) throws Exception
   {
      res.withStatus(status);
      res.withStatusCode(statusCode);

      JSNode json = getJson();

      if (json != null)
      {
         if (json instanceof JSArray)
            res.withData((JSArray) json);
         else
            res.withJson(json);
      }

      if (cancelRequest)
         chain.cancel();
   }

   public MockAction withJson(JSNode json)
   {
      this.json = json;
      return null;
   }

   public String getJsonUrl()
   {
      return jsonUrl;
   }

   public MockAction withJsonUrl(String jsonUrl)
   {
      this.jsonUrl = jsonUrl;
      return this;
   }

   public JSNode getJson()
   {
      if (json == null && jsonUrl != null)
      {
         InputStream stream = null;
         try
         {
            stream = new URL(jsonUrl).openStream();
         }
         catch (Exception ex)
         {
         }

         if (stream == null)
         {
            stream = getClass().getResourceAsStream(jsonUrl);
         }

         if (stream == null)
         {
            stream = getClass().getClassLoader().getResourceAsStream(jsonUrl);
         }

         if (stream == null)
         {
            try
            {
               File f = new File(jsonUrl);
               if (f.exists())
                  stream = new BufferedInputStream(new FileInputStream(jsonUrl));
            }
            catch (Exception ex)
            {
               ex.printStackTrace();
            }
         }

         if (stream != null)
         {
            json = JSNode.parseJsonNode(Utils.read(stream));
         }
         else
         {
            throw new ApiException(SC.SC_500_INTERNAL_SERVER_ERROR, "Unable to locate jsonUrl '" + jsonUrl + "'. Please check your configuration");
         }

      }

      return json;

   }

   public int getStatusCode()
   {
      return statusCode;
   }

   public MockAction StatusCode(int statusCode)
   {
      this.statusCode = statusCode;
      return this;
   }

   public String getStatus()
   {
      return status;
   }

   public MockAction withStatus(String status)
   {
      this.status = status;
      return this;
   }

   public boolean isCancelRequest()
   {
      return cancelRequest;
   }

   public MockAction withCancelRequest(boolean cancelRequest)
   {
      this.cancelRequest = cancelRequest;
      return this;
   }
}
