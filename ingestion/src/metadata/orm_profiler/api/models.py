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

"""
Return types for Profiler workflow execution.

We need to define this class as we end up having
multiple profilers per table and columns.
"""
from typing import List, Optional

from pydantic import BaseModel

from metadata.generated.schema.entity.data.table import Table, TableProfile
from metadata.orm_profiler.profiles.core import Profiler
from metadata.orm_profiler.profiles.models import ProfilerDef
from metadata.orm_profiler.validations.models import TestDef


class WorkflowResult(BaseModel):
    class Config:
        arbitrary_types_allowed = True


class ProfilerProcessorConfig(BaseModel):
    """
    Defines how we read the processor information
    from the workflow JSON definition
    """

    profiler: Optional[ProfilerDef] = None
    tests: Optional[TestDef] = None


class ProfileAndTests(BaseModel):
    """
    ORM Profiler processor response.

    For a given table, return all profilers and
    the ran tests, if any.
    """

    table: Table
    profile: TableProfile
    tests: Optional[TestDef] = None
