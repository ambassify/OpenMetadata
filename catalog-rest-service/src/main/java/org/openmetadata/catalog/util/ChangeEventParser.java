/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.catalog.util;

import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.json.stream.JsonParsingException;
import org.apache.commons.lang.StringUtils;
import org.openmetadata.catalog.Entity;
import org.openmetadata.catalog.resources.feeds.MessageParser.EntityLink;
import org.openmetadata.catalog.type.ChangeDescription;
import org.openmetadata.catalog.type.ChangeEvent;
import org.openmetadata.catalog.type.EntityReference;
import org.openmetadata.catalog.type.FieldChange;

public final class ChangeEventParser {

  private ChangeEventParser() {}

  private enum CHANGE_TYPE {
    UPDATE,
    ADD,
    DELETE
  }

  public static Map<EntityLink, String> getFormattedMessages(
      ChangeDescription changeDescription, Object entity, ChangeEvent changeEvent) {
    // Store a map of entityLink -> message
    Map<EntityLink, String> messages;
    Double previousVersion = changeDescription.getPreviousVersion();
    Double currentVersion = changeEvent.getCurrentVersion();

    List<FieldChange> fieldsUpdated = changeDescription.getFieldsUpdated();
    messages = getFormattedMessages(entity, fieldsUpdated, CHANGE_TYPE.UPDATE, previousVersion, currentVersion);

    // fieldsAdded and fieldsDeleted need special handling since
    // there is a possibility to merge them as one update message.
    List<FieldChange> fieldsAdded = changeDescription.getFieldsAdded();
    List<FieldChange> fieldsDeleted = changeDescription.getFieldsDeleted();
    messages.putAll(getFormattedMessages(entity, fieldsAdded, fieldsDeleted, previousVersion, currentVersion));

    return messages;
  }

  private static Map<EntityLink, String> getFormattedMessages(
      Object entity, List<FieldChange> fields, CHANGE_TYPE changeType, Double previousVersion, Double currentVersion) {
    Map<EntityLink, String> messages = new HashMap<>();

    for (var field : fields) {
      // if field name has dots, then it is an array field
      String fieldName = field.getName();

      String newFieldValue = getFieldValue(field.getNewValue());
      String oldFieldValue = getFieldValue(field.getOldValue());
      EntityLink link = getEntityLink(fieldName, entity);
      String message =
          getFormattedMessage(
              link, changeType, fieldName, oldFieldValue, newFieldValue, previousVersion, currentVersion);

      messages.put(link, message);
    }
    return messages;
  }

  private static String getFieldValue(Object fieldValue) {
    if (fieldValue != null) {
      try {
        // Check if field value is a json string
        JsonValue json = JsonUtils.readJson(fieldValue.toString());
        if (json.getValueType() == ValueType.ARRAY) {
          JsonArray jsonArray = json.asJsonArray();
          List<String> labels = new ArrayList<>();
          for (var item : jsonArray) {
            if (item.getValueType() == ValueType.OBJECT) {
              Set<String> keys = item.asJsonObject().keySet();
              if (keys.contains("tagFQN")) {
                labels.add(item.asJsonObject().getString("tagFQN"));
              } else if (keys.contains("displayName")) {
                // Entity Reference will have a displayName
                labels.add(item.asJsonObject().getString("displayName"));
              }
            }
          }
          return String.join(", ", labels);
        } else if (json.getValueType() == ValueType.OBJECT) {
          JsonObject jsonObject = json.asJsonObject();
          // Entity Reference will have a displayName
          Set<String> keys = jsonObject.asJsonObject().keySet();
          if (keys.contains("displayName")) {
            return jsonObject.asJsonObject().getString("displayName");
          }
        }
      } catch (JsonParsingException ex) {
        // If unable to parse json, just return the string
      }
      return fieldValue.toString();
    }
    return StringUtils.EMPTY;
  }

  /**
   * Tries to merge additions and deletions into updates and returns a map of formatted messages.
   *
   * @param entity Entity object.
   * @param addedFields Fields that were added as part of the change event.
   * @param deletedFields Fields that were deleted as part of the change event.
   * @return A map of entity link -> formatted message.
   */
  private static Map<EntityLink, String> getFormattedMessages(
      Object entity,
      List<FieldChange> addedFields,
      List<FieldChange> deletedFields,
      Double previousVersion,
      Double currentVersion) {
    // Major schema version changes such as renaming a column from colA to colB
    // will be recorded as "Removed column colA" and "Added column colB"
    // This method will try to detect such changes and combine those events into one update.

    Map<EntityLink, String> messages = new HashMap<>();

    // if there is only added fields or only deleted fields, we cannot merge
    if (addedFields.isEmpty() || deletedFields.isEmpty()) {
      if (!addedFields.isEmpty()) {
        messages = getFormattedMessages(entity, addedFields, CHANGE_TYPE.ADD, previousVersion, currentVersion);
      } else if (!deletedFields.isEmpty()) {
        messages = getFormattedMessages(entity, deletedFields, CHANGE_TYPE.DELETE, previousVersion, currentVersion);
      }
      return messages;
    }
    for (var field : deletedFields) {
      Optional<FieldChange> addedField =
          addedFields.stream().filter(f -> f.getName().equals(field.getName())).findAny();
      if (addedField.isPresent()) {
        String fieldName = field.getName();
        EntityLink link = getEntityLink(fieldName, entity);
        // convert the added field and deleted field into one update message
        String message =
            getFormattedMessage(
                link,
                CHANGE_TYPE.UPDATE,
                fieldName,
                field.getOldValue(),
                addedField.get().getNewValue(),
                previousVersion,
                currentVersion);
        messages.put(link, message);
        // Remove the field from addedFields list to avoid double processing
        addedFields = addedFields.stream().filter(f -> !f.equals(addedField.get())).collect(Collectors.toList());
      } else {
        // process the deleted field
        messages.putAll(
            getFormattedMessages(
                entity, Collections.singletonList(field), CHANGE_TYPE.DELETE, previousVersion, currentVersion));
      }
    }
    // process the remaining added fields
    if (!addedFields.isEmpty()) {
      messages.putAll(getFormattedMessages(entity, addedFields, CHANGE_TYPE.ADD, previousVersion, currentVersion));
    }
    return messages;
  }

  private static EntityLink getEntityLink(String fieldName, Object entity) {
    EntityReference entityReference = Entity.getEntityReference(entity);
    String entityType = entityReference.getType();
    String entityFQN = entityReference.getName();
    String arrayFieldName = null;
    String arrayFieldValue = null;

    if (fieldName.contains(".")) {
      String[] fieldNameParts = fieldName.split("\\.");
      // For array type, it should have 3 parts. ex: columns.comment.description
      fieldName = fieldNameParts[0];
      if (fieldNameParts.length == 3) {
        arrayFieldName = fieldNameParts[1];
        arrayFieldValue = fieldNameParts[2];
      } else if (fieldNameParts.length == 2) {
        arrayFieldName = fieldNameParts[1];
      }
    }

    return new EntityLink(entityType, entityFQN, fieldName, arrayFieldName, arrayFieldValue);
  }

  private static String getFormattedMessage(
      EntityLink link,
      CHANGE_TYPE changeType,
      String fieldName,
      Object oldFieldValue,
      Object newFieldValue,
      Double previousVersion,
      Double currentVersion) {
    String arrayFieldName = link.getArrayFieldName();
    String arrayFieldValue = link.getArrayFieldValue();

    String message = null;
    String updatedField = fieldName;
    if (arrayFieldValue != null) {
      updatedField = String.format("%s.%s", arrayFieldName, arrayFieldValue);
    } else if (arrayFieldName != null) {
      updatedField = String.format("%s.%s", fieldName, arrayFieldName);
    }

    switch (changeType) {
      case ADD:
        message = String.format("Added %s: `%s`", updatedField, getFieldValue(newFieldValue));
        break;
      case UPDATE:
        message = getUpdateMessage(updatedField, oldFieldValue, newFieldValue);
        break;
      case DELETE:
        message = String.format("Deleted %s", updatedField);
        break;
      default:
        break;
    }
    if (message != null) {
      // Double subtraction gives strange results which cannot be relied upon.
      // That is why using "> 0.9D" comparison instead of "== 1.0D"
      double versionDiff = currentVersion - previousVersion;
      String updateType = versionDiff > 0.9D ? "MAJOR" : "MINOR";
      message =
          String.format(
              "%s <br/><br/> **Change Type:** *%s (%s -> %s)*", message, updateType, previousVersion, currentVersion);
    }
    return message;
  }

  private static String getPlainTextUpdateMessage(String updatedField, String oldValue, String newValue) {
    // Get diff of old value and new value
    String diff = getPlaintextDiff(oldValue, newValue);
    return String.format("Updated %s : `%s`", updatedField, diff);
  }

  private static String getObjectUpdateMessage(String updatedField, JsonObject oldJson, JsonObject newJson) {
    List<String> labels = new ArrayList<>();
    Set<String> keys = newJson.keySet();
    // check if each key's value is the same
    for (var key : keys) {
      if (!newJson.get(key).equals(oldJson.get(key))) {
        labels.add(
            String.format("%s: `%s`", key, getPlaintextDiff(oldJson.get(key).toString(), newJson.get(key).toString())));
      }
    }
    String updates = String.join(" <br/> ", labels);
    // Include name of the field if the json contains "name" key
    if (newJson.containsKey("name")) {
      updatedField = String.format("%s.%s", updatedField, newJson.getString("name"));
    }
    return String.format("Updated %s : <br/> %s", updatedField, updates);
  }

  private static String getUpdateMessage(String updatedField, Object oldValue, Object newValue) {
    if (oldValue == null || oldValue.toString().isEmpty()) {
      return String.format("Updated %s to `%s`", updatedField, getFieldValue(newValue));
    } else if (updatedField.contains("tags") || updatedField.contains("owner")) {
      return getPlainTextUpdateMessage(updatedField, getFieldValue(oldValue), getFieldValue(newValue));
    }
    // if old value is not empty, and is of type array or object, the updates can be across multiple keys
    // Example: [{name: "col1", dataType: "varchar", dataLength: "20"}]

    try {
      // Check if field value is a json string
      JsonValue newJson = JsonUtils.readJson(newValue.toString());
      JsonValue oldJson = JsonUtils.readJson(oldValue.toString());
      if (newJson.getValueType() == ValueType.ARRAY) {
        JsonArray newJsonArray = newJson.asJsonArray();
        JsonArray oldJsonArray = oldJson.asJsonArray();
        if (newJsonArray.size() == 1 && oldJsonArray.size() == 1) {
          // if there is only one item in the array, it can be safely considered as an update
          JsonValue newItem = newJsonArray.get(0);
          JsonValue oldItem = oldJsonArray.get(0);
          if (newItem.getValueType() == ValueType.OBJECT) {
            JsonObject newJsonItem = newItem.asJsonObject();
            JsonObject oldJsonItem = oldItem.asJsonObject();
            return getObjectUpdateMessage(updatedField, oldJsonItem, newJsonItem);
          } else {
            return getPlainTextUpdateMessage(updatedField, newItem.toString(), oldItem.toString());
          }
        } else {
          return getPlainTextUpdateMessage(updatedField, getFieldValue(oldValue), getFieldValue(newValue));
        }
      } else if (newJson.getValueType() == ValueType.OBJECT) {
        JsonObject newJsonObject = newJson.asJsonObject();
        JsonObject oldJsonObject = oldJson.asJsonObject();
        return getObjectUpdateMessage(updatedField, oldJsonObject, newJsonObject);
      }
    } catch (JsonParsingException ex) {
      // update is of String type
      return getPlainTextUpdateMessage(updatedField, oldValue.toString(), newValue.toString());
    }
    return StringUtils.EMPTY;
  }

  private static String getPlaintextDiff(String oldValue, String newValue) {
    // create a configured DiffRowGenerator
    String addMarker = "<!add>";
    String removeMarker = "<!remove>";
    DiffRowGenerator generator =
        DiffRowGenerator.create()
            .showInlineDiffs(true)
            .mergeOriginalRevised(true)
            .inlineDiffByWord(true)
            .oldTag(f -> removeMarker) // introduce a tag to mark removals
            .newTag(f -> addMarker) // introduce a tag to mark new additions
            .build();
    // compute the differences
    List<DiffRow> rows = generator.generateDiffRows(List.of(oldValue), List.of(newValue));

    // There will be only one row of output
    String diff = rows.get(0).getOldLine();

    // The additions and removals will be wrapped by <!add> and <!remove> tags
    // Replace them with html tags to render nicely in the UI
    // Example: This is a test <!remove>sentence<!remove><!add>line<!add>
    // This is a test <span class="diff-removed">sentence</span><span class="diff-added">line</span>
    String spanAdd = "<span class=\"diff-added\">";
    String spanRemove = "<span class=\"diff-removed\">";
    String spanClose = "</span>";
    diff = replaceWithHtml(diff, addMarker, spanAdd, spanClose);
    diff = replaceWithHtml(diff, removeMarker, spanRemove, spanClose);
    return diff;
  }

  private static String replaceWithHtml(String diff, String marker, String openTag, String closeTag) {
    int index = 0;
    while (diff.contains(marker)) {
      String replacement = index % 2 == 0 ? openTag : closeTag;
      diff = diff.replaceFirst(marker, replacement);
      index++;
    }
    return diff;
  }
}
