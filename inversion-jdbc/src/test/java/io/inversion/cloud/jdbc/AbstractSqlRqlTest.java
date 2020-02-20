/*
 * Copyright (c) 2015-2020 Rocket Partners, LLC
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
package io.inversion.cloud.jdbc;

import org.junit.jupiter.api.AfterAll;

import io.inversion.cloud.action.rest.RestAction;
import io.inversion.cloud.jdbc.db.JdbcDb;
import io.inversion.cloud.jdbc.db.JdbcDb.ConnectionLocal;
import io.inversion.cloud.jdbc.rql.SqlQuery;
import io.inversion.cloud.model.Api;
import io.inversion.cloud.model.Db;
import io.inversion.cloud.rql.AbstractRqlTest;
import io.inversion.cloud.rql.RqlValidationSuite;
import io.inversion.cloud.service.Engine;

public abstract class AbstractSqlRqlTest extends AbstractRqlTest
{

   public AbstractSqlRqlTest(String dbType)
   {
      super(SqlQuery.class.getName(), dbType);
   }

   @Override
   public Db buildUnitTestDb()
   {
      return new JdbcDb(dbType).withType(dbType);
   }

   @Override
   public Engine buildIntegTestEngine() throws Exception
   {
      //      if (engine != null)
      //      {
      //         engine.getApi("northwind").getDb(dbType).shutdown();
      //      }

      Engine engine = new Engine().withApi(new Api("northwind") //
                                                               .withEndpoint("GET", dbType + "/*", new RestAction())//
                                                               .withDb(JdbcDbFactory.buildDb(dbType, getClass().getName())));

      engine.startup();

      return engine;
   }

   @AfterAll
   public void afterAll_shutdownDb()
   {
      if (engine != null)
      {
         engine.getApi("northwind").getDb(dbType).shutdown();
      }

      try
      {
         ConnectionLocal.close();
      }
      catch (Exception ex)
      {
         ex.printStackTrace();
      }
   }

   /**
    * The majority of these should be postgres/h2 compatible.  Mysql and MsSQL 
    * will probably have to customize most of these.
    */
   @Override
   protected void customizeUnitTestSuite(RqlValidationSuite suite)
   {

      suite//
           .withResult("eq", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"orderID\" = ? AND \"orders\".\"shipCountry\" = ? ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[10248, France]")//
           .withResult("ne", "SELECT \"orders\".* FROM \"orders\" WHERE (NOT (\"orders\".\"shipCountry\" = ?)) ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[France]")//
           .withResult("n", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"shipRegion\" IS NULL ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[]")//
           .withResult("nn", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"shipRegion\" IS NOT NULL ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[]")//
           .withResult("emp", "SELECT \"orders\".* FROM \"orders\" WHERE (\"orders\".\"shipRegion\" IS NULL OR \"orders\".\"shipRegion\" = '') ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[]")//
           .withResult("nemp", "SELECT \"orders\".* FROM \"orders\" WHERE (\"orders\".\"shipRegion\" IS NOT NULL AND \"orders\".\"shipRegion\" != '') ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[]")//
           .withResult("likeMiddle", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"shipCountry\" ILIKE ? ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[F%ance]")//
           .withResult("likeStartsWith", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"shipCountry\" ILIKE ? ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[Franc%]")//
           .withResult("likeEndsWith", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"shipCountry\" ILIKE ? ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[%ance]")//
           .withResult("sw", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"shipCountry\" ILIKE ? ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[Franc%]")//
           .withResult("ew", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"shipCountry\" ILIKE ? ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[%nce]")//
           .withResult("w", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"shipCountry\" ILIKE ? ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[%ance%]")//
           .withResult("wo", "SELECT \"orders\".* FROM \"orders\" WHERE (NOT (\"orders\".\"shipCountry\" ILIKE ?)) ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[%ance%]")//
           .withResult("lt", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"freight\" < ? ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[10]")//
           .withResult("le", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"freight\" <= ? ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[10]")//
           .withResult("gt", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"freight\" > ? ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[3.67]")//
           .withResult("ge", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"freight\" >= ? ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[3.67]")//
           .withResult("in", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"shipCity\" IN(?, ?) ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[Reims, Charleroi]")//
           .withResult("out", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"shipCity\" NOT IN(?, ?) ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[Reims, Charleroi]")//
           .withResult("and", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"orderID\" = ? AND \"orders\".\"shipCountry\" = ? ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[10248, France]")//
           .withResult("or", "SELECT \"orders\".* FROM \"orders\" WHERE (\"orders\".\"shipCity\" = ? OR \"orders\".\"shipCity\" = ?) ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[Reims, Charleroi]")//
           .withResult("not", "SELECT \"orders\".* FROM \"orders\" WHERE NOT ((\"orders\".\"shipCity\" = ? OR \"orders\".\"shipCity\" = ?)) ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[Reims, Charleroi]")//
           .withResult("as", "SELECT \"orders\".*, \"orders\".\"orderid\" AS \"order_identifier\" FROM \"orders\" ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[]")//
           .withResult("includes", "SELECT \"orders\".\"shipCountry\", \"orders\".\"shipCity\", \"orders\".\"orderId\" FROM \"orders\" ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[]")//
           .withResult("distinct", "SELECT DISTINCT \"orders\".\"shipCountry\" FROM \"orders\" ORDER BY \"orders\".\"shipCountry\" ASC LIMIT 100 OFFSET 0 args=[]")//
           .withResult("count1", "SELECT COUNT(*) FROM \"orders\" args=[]")//
           .withResult("count2", "SELECT COUNT(?) FROM \"orders\" args=[1]")//
           .withResult("count3", "SELECT COUNT(\"orders\".\"shipRegion\") FROM \"orders\" args=[]")//
           .withResult("countAs", "SELECT COUNT(*) AS \"countOrders\" FROM \"orders\" args=[]")//
           .withResult("sum", "SELECT SUM(\"orders\".\"freight\") FROM \"orders\" args=[]")//
           .withResult("sumAs", "SELECT SUM(\"orders\".\"freight\") AS \"Sum Freight\" FROM \"orders\" args=[]")//
           .withResult("sumIf", "SELECT SUM(CASE WHEN \"orders\".\"shipCountry\" = ? THEN 1 ELSE 0 END) AS \"French Orders\" FROM \"orders\" args=[France]")//
           .withResult("min", "SELECT MIN(\"orders\".\"freight\") FROM \"orders\" args=[]")//
           .withResult("max", "SELECT MAX(\"orders\".\"freight\") FROM \"orders\" args=[]")//
           .withResult("groupCount", "SELECT \"orders\".\"shipCountry\", COUNT(*) AS \"countryCount\" FROM \"orders\" GROUP BY \"orders\".\"shipCountry\" ORDER BY \"orders\".\"shipCountry\" ASC LIMIT 100 OFFSET 0 args=[]")//
           .withResult("offset", "SELECT \"orders\".* FROM \"orders\" ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 3 args=[]")//
           .withResult("limit", "SELECT \"orders\".* FROM \"orders\" ORDER BY \"orders\".\"orderId\" ASC LIMIT 7 OFFSET 0 args=[]")//
           .withResult("page", "SELECT \"orders\".* FROM \"orders\" ORDER BY \"orders\".\"orderId\" ASC LIMIT 7 OFFSET 14 args=[]")//
           .withResult("pageNum", "SELECT \"orders\".* FROM \"orders\" ORDER BY \"orders\".\"orderId\" ASC LIMIT 7 OFFSET 14 args=[]")//
           .withResult("after", "UNSUPPORTED")//
           .withResult("sort", "SELECT \"orders\".* FROM \"orders\" ORDER BY \"orders\".\"shipCountry\" DESC, \"orders\".\"shipCity\" ASC LIMIT 100 OFFSET 0 args=[]")//
           .withResult("order", "SELECT \"orders\".* FROM \"orders\" ORDER BY \"orders\".\"shipCountry\" ASC, \"orders\".\"shipCity\" DESC LIMIT 100 OFFSET 0 args=[]")//
      ;
   }
}
