#  Copyright 2021 Collate
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#  http://www.apache.org/licenses/LICENSE-2.0
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

from typing import Optional

from metadata.ingestion.source.redshift import RedshiftConfig
from metadata.profiler.common.database_common import DatabaseCommon, SQLExpressions


class RedshiftSQLExpressions(SQLExpressions):
    avg_expr = "AVG({})"
    sum_expr = "SUM({})"
    regex_like_pattern_expr: str = "{} ~* '{}'"


class Redshift(DatabaseCommon):
    config: RedshiftConfig = None
    sql_exprs: RedshiftSQLExpressions = RedshiftSQLExpressions()

    def __init__(self, config):
        super().__init__(config)
        self.config = config

    @classmethod
    def create(cls, config_dict):
        config = RedshiftConfig.parse_obj(config_dict)
        return cls(config)
