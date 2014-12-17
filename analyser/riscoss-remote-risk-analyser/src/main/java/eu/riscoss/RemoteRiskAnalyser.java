/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package eu.riscoss;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.io.File;

import org.json.JSONObject;
import org.json.JSONArray;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.FileUtils;

import eu.riscoss.reasoner.ReasoningLibrary;
import eu.riscoss.reasoner.RiskAnalysisEngine;
import eu.riscoss.reasoner.Chunk;
import eu.riscoss.reasoner.ModelSlice;
import eu.riscoss.reasoner.Field;
import eu.riscoss.reasoner.FieldType;
import eu.riscoss.reasoner.Evidence;
import eu.riscoss.reasoner.Distribution;

import org.apache.commons.lang3.exception.ExceptionUtils;

public class RemoteRiskAnalyser
{
    static final String BEGIN_MARKER = "-----BEGIN ANALYSIS OUTPUT-----";
    static final String END_MARKER = "-----END ANALYSIS OUTPUT-----";

    static class RiskDataAndErrors
    {
        Map<String, Object> riskData;
        Map<String, String> errors;
    }

    static List<String> setRiskData(RiskAnalysisEngine riskAnalysisEngine,
                                    Map<String, Object> riskData)
    {
        Iterable<Chunk> chunks = riskAnalysisEngine.queryModel(ModelSlice.INPUT_DATA);
        List<String> warnings = new ArrayList<String>();
        for (Chunk chunk : chunks) {
            Field field = riskAnalysisEngine.getField(chunk, FieldType.INPUT_VALUE);

            Object value = riskData.get(chunk.getId());
            if (value != null) {
                switch (field.getDataType()) {
                    case INTEGER:
                        if (value instanceof Integer) {
                            field.setValue(value);
                            riskAnalysisEngine.setField(chunk, FieldType.INPUT_VALUE, field);
                        } else {
                            warnings.add(String.format(
                                    "Retrieved risk data for %s has the wrong type. Expected double, got %s",
                                    chunk.getId(), value.getClass().getName()));
                        }
                        break;
                    case REAL:
                        if (value instanceof Double) {
                            field.setValue(value);
                            riskAnalysisEngine.setField(chunk, FieldType.INPUT_VALUE, field);
                        } else {
                            warnings.add(String.format(
                                    "Retrieved risk data for %s has the wrong type. Expected double, got %s",
                                    chunk.getId(), value.getClass().getName()));
                        }
                        break;
                    case EVIDENCE:
                        if (value instanceof Evidence) {
                            field.setValue(value);
                            riskAnalysisEngine.setField(chunk, FieldType.INPUT_VALUE, field);
                        } else {
                            warnings.add(String.format(
                                    "Retrieved risk data for %s has the wrong type. Evidence double, got %s",
                                    chunk.getId(), value.getClass().getName()));
                        }
                        break;
                    case DISTRIBUTION:
                        if (value instanceof Distribution) {
                            if (((Distribution) value).getValues().size() ==
                                    ((Distribution) field.getValue()).getValues().size())
                            {
                                field.setValue(value);
                                riskAnalysisEngine.setField(chunk, FieldType.INPUT_VALUE, field);
                            } else {
                                warnings.add(String.format(
                                        "Retrieved risk data for %s has the wrong size. Expected %d-Distribution, got %d-Distribution",
                                        chunk.getId(), ((Distribution) value).getValues().size(),
                                        ((Distribution) field.getValue()).getValues().size()));
                            }
                        } else {
                            warnings.add(String.format(
                                    "Retrieved risk data for %s has the wrong type. Expected Distribution, got %s",
                                    chunk.getId(), value.getClass().getName()));
                        }
                        break;
                }
            }
        }
        return warnings;
    }

    static RiskDataAndErrors getRiskDataFromRequest(RiskAnalysisEngine riskAnalysisEngine,
                                                    Map<String, String[]> requestParams)
    {
        Map<String, Object> riskData = new HashMap<String, Object>();
        Map<String, String> errors = new HashMap<String, String>();

        Iterable<Chunk> chunks = riskAnalysisEngine.queryModel(ModelSlice.INPUT_DATA);
        for (Chunk chunk : chunks) {
            Field field = riskAnalysisEngine.getField(chunk, FieldType.INPUT_VALUE);
            try {

                String[] values = requestParams.get(chunk.getId());

                switch (field.getDataType()) {
                    case INTEGER:
                        int i = 0;

                        if (values != null && !values[0].isEmpty()) {
                            i = Integer.parseInt(values[0]);
                        }

                        riskData.put(chunk.getId(), i);
                        break;
                    case REAL:
                        double d = 0.0d;

                        if (values != null && !values[0].isEmpty()) {
                            d = Double.parseDouble(values[0]);
                        }

                        riskData.put(chunk.getId(), d);
                        break;
                    case EVIDENCE:
                        if (values != null && values.length == 2) {
                            double p = 0.0;
                            double n = 0.0;

                            if (!values[0].isEmpty()) {
                                p = Double.parseDouble(values[0]);
                            }

                            if (!values[1].isEmpty()) {
                                n = Double.parseDouble(values[1]);
                            }

                            Evidence evidence = new Evidence(p, n);
                            riskData.put(chunk.getId(), evidence);
                        } else {
                            errors.put(chunk.getId(), "Two values are required");
                        }
                        break;
                    case DISTRIBUTION:
                        List<Double> distributionValues = new ArrayList<Double>();
                        if (values != null) {
                            for (String v : values) {
                                if (!v.isEmpty()) {
                                    distributionValues.add(Double.parseDouble(v));
                                } else {
                                    distributionValues.add(0.0d);
                                }
                            }
                        } else {
                            for (int n = 0; n < ((Distribution) field.getValue()).getValues().size(); n++) {
                                distributionValues.add(0.0d);
                            }
                        }

                        Distribution distribution = new Distribution();
                        distribution.setValues(distributionValues);
                        riskData.put(chunk.getId(), distribution);
                        break;
                    case STRING:
                        riskData.put(chunk.getId(), values[0]);
                        break;
                }
            } catch (NumberFormatException e) {
                errors.put(chunk.getId(), String.format("Invalid number format for %s", field.getDataType()));
            } catch (Exception e) {
                errors.put(chunk.getId(), e.getMessage());
            }
        }

        RiskDataAndErrors result = new RiskDataAndErrors();
        result.riskData = riskData;
        result.errors = errors;

        return result;
    }

    static Map<String, Map<String, Object>> runAnalysis(RiskAnalysisEngine riskAnalysisEngine)
    {
        Map<String, Map<String, Object>> result = new HashMap<String, Map<String, Object>>();

        riskAnalysisEngine.runAnalysis(new String[0]);

        Iterable<Chunk> chunks = riskAnalysisEngine.queryModel(ModelSlice.OUTPUT_DATA);
        for (Chunk chunk : chunks) {
            Field field = riskAnalysisEngine.getField(chunk, FieldType.OUTPUT_VALUE);

            Map<String, Object> item = new HashMap<String, Object>();
            Field descriptionField = riskAnalysisEngine.getField(chunk, FieldType.DESCRIPTION);
            if (descriptionField != null) {
                item.put("DESCRIPTION", riskAnalysisEngine.getField(chunk, FieldType.DESCRIPTION).getValue());
            } else {
                item.put("DESCRIPTION", chunk.getId());
            }
            item.put("TYPE", field.getDataType().toString());
            item.put("VALUE", field.getValue());

            result.put(chunk.getId(), item);
        }

        return result;
    }

    static Map<String, String[]> unpackInputs(JSONObject obj)
    {
        Map<String, String[]> out = new HashMap<String, String[]>();
        for (String key : JSONObject.getNames(obj)) {
            JSONObject entryObj = obj.getJSONObject(key);
            String type = entryObj.getString("type");
            if ("DISTRIBUTION".equals(type) || "EVIDENCE".equals(type)) {
                JSONArray arr = entryObj.getJSONArray("value");
                String[] x = new String[arr.length()];
                for (int i = 0; i < x.length; i++) {
                    x[i] = arr.get(i).toString();
                }
                out.put(key, x);
            } else if ("STRING".equals(type)) {
                out.put(key, new String[] { entryObj.getString("value") });
            } else if ("INTEGER".equals(type)) {
                out.put(key, new String[] { ("" + entryObj.getInt("value")) });
            } else if ("REAL".equals(type)) {
                out.put(key, new String[] { ("" + entryObj.getDouble("value")) });
            } else {
                throw new RuntimeException("unexpected type [" + type + "]");
            }
        }
        return out;
    }

    static JSONObject getInputs(RiskAnalysisEngine rae)
    {
        Iterable<Chunk> chunks = rae.queryModel(ModelSlice.INPUT_DATA);
        JSONObject out = new JSONObject();
        for (Chunk chunk : chunks) {
            JSONObject o = new JSONObject();
            Field field = rae.getField(chunk, FieldType.INPUT_VALUE);
            String dataType = field.getDataType().toString();
            o.put("type", dataType);
            if (rae.getField(chunk, FieldType.DESCRIPTION) != null) {
                o.put("description", rae.getField(chunk, FieldType.DESCRIPTION).getValue());
            }
            if (rae.getField(chunk, FieldType.QUESTION) != null) {
                o.put("question", rae.getField(chunk, FieldType.QUESTION).getValue());
            }
            if ("EVIDENCE".equals(dataType)) {
                o.put("value", new JSONArray("[0,0]"));
            } else if ("DISTRIBUTION".equals(dataType)) {
                int size = 1;
                if (field.getValue() == null) {
                    // fallthrough
                } else if (((Distribution)field.getValue()) == null) {
                    // fallthrough
                } else {
                    size = ((Distribution)field.getValue()).getValues().size();
                }
                JSONArray arr = new JSONArray();
                for (int i = 0; i < size; i++) {
                    if (i == 0) {
                        arr.put(1);
                    } else {
                        arr.put(0);
                    }
                }
                o.put("value", arr);
            } else if ("REAL".equals(dataType)) {
                o.put("value", 0.0);
            } else if ("INTEGER".equals(dataType)) {
                o.put("value", 0);
            } else if ("STRING".equals(dataType)) {
                o.put("value", "");
            } else {
                throw new RuntimeException("unexpected data type [" + dataType + "]");
            }
            out.put(chunk.getId(), o);
        }
        return out;
    }

    static void load(JSONObject in, JSONObject out)
    {
        RiskAnalysisEngine engine = ReasoningLibrary.get().createRiskAnalysisEngine();
        JSONArray riskModels = in.getJSONArray("riskModels");
        for (int i = 0; i < riskModels.length(); i++) {
            engine.loadModel(riskModels.getString(i));
        }
        String action = in.getString("action");
        if ("getInputs".equals(action)) {
            out.put("result", getInputs(engine));
        } else if ("evaluate".equals(action)) {
            Map<String, String[]> rm = unpackInputs(in.getJSONObject("inputs"));
            RiskDataAndErrors res = getRiskDataFromRequest(engine, rm);
            if (res.errors.size() > 0) {
                out.put("errors", new JSONObject(res.errors));
            } else {
                List<String> warnings = setRiskData(engine, res.riskData);
                out.put("warnings", new JSONArray(warnings));
                Map<String, Map<String, Object>> m = runAnalysis(engine);
                // use net.sf.json for this particular field to preserve backward compat behavior.
                out.put("result", new JSONObject(m));
            }
        }
    }

    static void printOutput(JSONObject out)
    {
        System.out.println(BEGIN_MARKER);
        System.out.println(out.toString(2));
        System.out.println(END_MARKER);
    }

    static JSONObject getInputs(String in)
    {
        if (in.indexOf(END_MARKER) != -1) {
            in = in.substring(0, in.indexOf(END_MARKER));
        }
        if (in.indexOf(BEGIN_MARKER) != -1) {
            in = in.substring(in.indexOf(BEGIN_MARKER) + BEGIN_MARKER.length());
        }
        JSONObject out = new JSONObject(in);
        return out.getJSONObject("result");
    }

    static void withArgs(String[] args) throws Exception
    {
        JSONObject request = new JSONObject();
        if ("evaluate".equals(args[0]) || "getInputs".equals(args[0])) {
            String modelFilesStr = args[1];
            JSONArray models = new JSONArray();
            String[] modelsArray = modelFilesStr.split(",");
            for (int i = 0; i < modelsArray.length; i++) {
                models.put(FileUtils.readFileToString(new File(modelsArray[i]), "UTF-8"));
            }
            request.put("riskModels", models);
        } else {
            System.out.println(
                "Usage: evaluate <model>[,<model2>[,<model3>...]] <inputDataFile>\n" +
                "       getInputs <model>[,<model2>[,<model3>...]]"
            );
            return;
        }

        if ("evaluate".equals(args[0])) {
            String inputFile = args[2];
            if ("-".equals(inputFile)) {
                request.put("inputs", getInputs(IOUtils.toString(System.in, "UTF-8")));
            } else {
                
                request.put("inputs",
                    getInputs(FileUtils.readFileToString(new File(inputFile), "UTF-8")));
            }
            request.put("action", "evaluate");
        } else {
            request.put("action", "getInputs");
        }
        JSONObject out = new JSONObject();
        load(request, out);
        printOutput(out);
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length > 0) {
            withArgs(args);
            return;
        }
        JSONObject out = new JSONObject();
        String stdin = IOUtils.toString(System.in, "UTF-8");
        JSONObject input = new JSONObject(stdin);
        load(input, out);
        printOutput(out);
    }
}
