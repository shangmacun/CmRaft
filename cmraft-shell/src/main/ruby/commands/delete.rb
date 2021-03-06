#
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

module Shell
  module Commands
    class Delete 
      def help
        return <<-EOF
  Delete the key value pair from Raft specified by the key, example:
  cmraft> delete k1
EOF
      end
      
      def usage
        return <<-EOF
  usage: delete <key> 
EOF
      end

      def command(*args)
        if(args.size != 1) then
          print usage
          return
        end
		conn = Java::com.chicm.cmraft.ConnectionManager.getConnection()		
		kvs = conn.getKeyValueStore()
		
		success = kvs.delete(args[0])
		if(success==true) then
		  puts "done"
		else
		  puts "failed"
		end	
		conn.close()	
        end
      end
    end
end

