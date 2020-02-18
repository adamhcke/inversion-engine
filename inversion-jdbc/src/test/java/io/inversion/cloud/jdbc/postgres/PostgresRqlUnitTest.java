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
package io.inversion.cloud.jdbc.postgres;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import io.inversion.cloud.jdbc.AbstractSqlRqlTest;
import io.inversion.cloud.rql.RqlValidationSuite;

@TestInstance(Lifecycle.PER_CLASS)
public class PostgresRqlUnitTest extends AbstractSqlRqlTest
{
   public PostgresRqlUnitTest() throws Exception
   {
      db = PostgresUtils.bootstrapPostgres(PostgresRqlUnitTest.class.getSimpleName());
   }

   @AfterAll
   public void afterAll_shutdownDb()
   {
      if (engine != null)
      {
         engine.getApi("northwind").getDb("postgres").shutdown();
      }
   }

   @Override
   protected void customizeUnitTestSuite(RqlValidationSuite suite)
   {
      super.customizeUnitTestSuite(suite);

      //-- add db specific customizations to the generated sql  
      //-- below like the commented out example
      //
      //      suite//
      //           .withResult("likeStartsWith", "SELECT \"orders\".* FROM \"orders\" WHERE \"orders\".\"shipCountry\" ILIKE ? ORDER BY \"orders\".\"orderId\" ASC LIMIT 100 OFFSET 0 args=[Franc%]")//
      //           .withResult("testKey", "your sql")//
      //           .withResult("testKey", "UNSUPPORTED")//
      //      ;
   }
}
