/*
 * Copyright (c) 2015-2019 Inversion.org, LLC
 * https://github.com/inversion-api
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.inversion.cloud.action.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.inversion.cloud.model.Action;
import io.inversion.cloud.model.Api;
import io.inversion.cloud.model.ApiException;
import io.inversion.cloud.model.JSArray;
import io.inversion.cloud.model.Collection;
import io.inversion.cloud.model.Endpoint;
import io.inversion.cloud.model.JSNode;
import io.inversion.cloud.model.Request;
import io.inversion.cloud.model.Response;
import io.inversion.cloud.model.SC;
import io.inversion.cloud.model.Url;
import io.inversion.cloud.rql.Parser;
import io.inversion.cloud.rql.Term;
import io.inversion.cloud.service.Chain;
import io.inversion.cloud.service.Engine;
import io.inversion.cloud.utils.Utils;

public class RestDeleteAction extends Action<RestDeleteAction>
{
   public RestDeleteAction()
   {
      this(null);
   }

   public RestDeleteAction(String inludePaths)
   {
      this(inludePaths, null, null);
   }

   public RestDeleteAction(String inludePaths, String excludePaths, String config)
   {
      super(inludePaths, excludePaths, config);
      withMethods("DELETE");
   }

   @Override
   public void run(Engine engine, Api api, Endpoint endpoint, Chain chain, Request req, Response res) throws Exception
   {
      String entityKey = req.getEntityKey();
      String subcollectionKey = req.getSubCollectionKey();
      JSNode json = req.getJson();

      int count = Utils.empty(entityKey) ? 0 : 1;
      count += Utils.empty(req.getQuery()) ? 0 : 1;
      count += json == null ? 0 : 1;

      if (count != 1)
         throw new ApiException(SC.SC_400_BAD_REQUEST, "DELETE expects an entity url, OR a query string OR a JSON array of entity urls, but only one at a time.");

      if (!Utils.empty(subcollectionKey))
         throw new ApiException(SC.SC_400_BAD_REQUEST, "A subcollection key is not valid for a DELETE request");

      List<String> toDelete = new ArrayList();

      if (req.getJson() != null)
      {
         if (!(json instanceof JSArray))
         {
            throw new ApiException(SC.SC_400_BAD_REQUEST, "The JSON body to a DELETE must be an array that contains string urls.");
         }

         for (Object o : (JSArray) json)
         {
            if (!(o instanceof String))
               throw new ApiException(SC.SC_400_BAD_REQUEST, "The JSON body to a DELETE must be an array that contains string urls.");

            String url = (String) o;

            String path = req.getUrl().toString();
            if (path.indexOf("?") > 0)
            {
               path = path.substring(0, path.indexOf("?") - 1);
            }

            if (!url.toLowerCase().startsWith(path.toLowerCase()))
            {
               throw new ApiException(SC.SC_400_BAD_REQUEST, "All delete request must be for the collection in the original request: '" + path + "'");
            }
            toDelete.add((String) o);
         }
      }
      else if (entityKey != null)
      {
         toDelete.add(req.getUrl().toString());
      }
      else
      {
         toDelete.add(req.getUrl().toString());
      }

      String collectionUrl = req.getApiUrl();
      if(!collectionUrl.endsWith("/"))
         collectionUrl += "/";
      
      collectionUrl += Utils.implode("/", req.getEndpointPath().toString(), req.getCollectionKey().toString());
      int deleted = delete(req, req.getCollection(), collectionUrl, toDelete);

      if (deleted < 1)
         res.withStatus(SC.SC_404_NOT_FOUND);
      else
         res.withStatus(SC.SC_204_NO_CONTENT);
   }

   protected int delete(Request req, Collection collection, String collectionUrl, List<String> urls) throws Exception
   {
      int deleted = 0;
      //------------------------------------------------
      // Normalize all of the params and convert attribute
      // names to column names.

      //      List<Term> terms = new ArrayList();
      //      terms.add(Term.term(null, "eq", "includes", "href"));

      Term or = Term.term(null, "or");
      Term in = Term.term(null, "_key", req.getCollection().getEntity().getTable().getPrimaryIndex().getName());

      Parser parser = new Parser();
      for (String u : urls)
      {
         Url url = new Url(u);
         Map<String, String> params = url.getParams();
         if (params.size() == 0)
         {
            List<String> path = url.getPath().parts();
            String key = path.get(path.size() - 1);
            in.withTerm(Term.term(in, key));
         }
         else
         {
            Term and = Term.term(null, "and");
            for (String paramName : params.keySet())
            {
               String termStr = null;
               String paramValue = params.get(paramName);

               if (Utils.empty(paramValue) && paramName.indexOf("(") > -1)
               {
                  termStr = paramName;
               }
               else
               {
                  termStr = "eq(" + paramName + "," + paramValue + ")";
               }
               Term term = parser.parse(termStr);
               and.withTerm(term);
            }
            if (and.size() == 0)
            {
               throw new ApiException(SC.SC_400_BAD_REQUEST, "You can't DELETE to a collection unless you include an entityKey or query string");
            }
            else
            {
               or.withTerm(and);
            }
         }
      }

      Term query = in;
      if (or.size() > 0)
      {
         if (in.size() > 1)
            or.withTerm(in);

         if (or.size() == 1)
            query = or.getTerm(0);
         else
            query = or;

      }

      String url = collectionUrl + "?" + query + "&page=1&pageSize=100&includes=href";

      for (int i = 0; i < 1000; i++)
      {

         //regardless of the query string passed in, this should resolve the keys 
         //that need to be deleted and make sure the uses has read access to the key
         //
         //TODO: need to do more tests here
         Response res = req.getEngine().get(url).statusOk();

         if (res.data().size() == 0)
            break;

         deleted += res.data().size();

         List<String> entityKeys = new ArrayList();
         res.data().asList().forEach(o -> entityKeys.add((String) Utils.last(Utils.explode("/", ((JSNode) o).getString("href")))));
         req.getCollection().getDb().delete(collection.getTable(), entityKeys);
      }

      return deleted;
   }
}
