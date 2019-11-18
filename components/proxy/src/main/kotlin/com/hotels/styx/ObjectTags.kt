/*
  Copyright (C) 2013-2019 Expedia Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package com.hotels.styx

fun lbGroupTag(name: String) = "lbGroup=$name"

fun lbGroupTagValue(tag: String): String? = "lbGroup=(.+)".toRegex()
        .matchEntire(tag)
        ?.groupValues
        ?.get(1)

fun sourceTag(creator: String) = "source=$creator"

fun sourceTag(tags: Set<String>) = tags.firstOrNull { it.startsWith("source=") }

fun sourceTagValue(tags: Set<String>) = sourceTag(tags)?.substring("source".length + 1)
