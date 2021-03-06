package com.redmoon.oa.visual;

import com.redmoon.kit.util.FileInfo;
import com.redmoon.kit.util.FileUpload;
import com.redmoon.oa.flow.macroctl.MacroCtlUnit;

import cn.js.fan.db.ResultIterator;
import cn.js.fan.db.ResultRecord;
import cn.js.fan.util.DateUtil;
import cn.js.fan.util.ErrMsgException;
import cn.js.fan.util.ParamChecker;
import cn.js.fan.util.ParamUtil;
import cn.js.fan.util.StrUtil;
import cn.js.fan.util.file.FileUtil;
import cn.js.fan.web.Global;

import com.redmoon.oa.flow.macroctl.MacroCtlMgr;
import com.redmoon.oa.flow.query.QueryScriptUtil;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;

import org.json.JSONException;
import org.json.JSONObject;

import com.redmoon.oa.flow.FormDb;
import com.redmoon.oa.flow.FormField;
import com.redmoon.oa.flow.FormQueryDb;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.cloudwebsoft.framework.db.JdbcTemplate;
import com.cloudwebsoft.framework.util.LogUtil;
import com.redmoon.oa.base.IFormDAO;
import com.redmoon.oa.base.IFormMacroCtl;

/**
 * <p>Title: </p>
 *
 * <p>Description: </p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 * 
 * <p>Company: </p>
 * 
 * @author not attributable
 * @version 1.0
 */
public class FormUtil {
	public static final String ERROR_PARSE = "ERR_PARSE";
	
    public FormUtil() {
    }

    /**
     * PC端生成检测字段是否唯一的脚本
     * @param id
     * @param fields
     * @return
     */
    public static String doGetCheckJSUnique(long id, Vector fields) {
        StringBuffer sbFields = new StringBuffer();
        Iterator ir = fields.iterator();
        while (ir.hasNext()) {
            FormField ff = (FormField) ir.next();
            if (ff.isUnique()) {
                StrUtil.concat(sbFields, ",", ff.getName());
            }
        }

        StringBuffer sb = new StringBuffer();
        sb.append("<script>\n");
        sb.append("$(function() {\n");
        ir = fields.iterator();
        while (ir.hasNext()) {
            FormField ff = (FormField)ir.next();
            if (ff.isUnique()) {
                sb.append("checkFieldIsUnique(" + id + ", '" + ff.getFormCode() + "', '" + ff.getName() + "', '" + sbFields.toString() + "');\n"); // 如果是添加页面，则id为-1
            }
        }
        sb.append("})\n");
        sb.append("</script>\n");
        return sb.toString();
    }

   /**
     * 生成前台验证JS脚本
     * @param request HttpServletRequest
     * @param fields Vector
     * @return String
     */
    public static String doGetCheckJS(HttpServletRequest request, Vector fields) {
        MacroCtlMgr mm = new MacroCtlMgr();
        ParamChecker pck = new ParamChecker(request);
        StringBuffer sb = new StringBuffer();
        String someNotNullFieldName = "";
        sb.append("<script>\n");
        FormField ffSubmit = null;
        Iterator ir = fields.iterator();
        while (ir.hasNext()) {
            FormField ff = (FormField) ir.next();
            if (ff==null) {
            	continue;
            }
            if (!ff.isEditable())
                continue;
            
            if (ff.getHide()!=FormField.HIDE_NONE)
            	continue;

            // 文件框不作前台是否为空的验证，否则当流程中保存草稿后，再处理时仍会提醒不能为空
            if (ff.getMacroType().equals("macro_attachment")) {
                continue;
            }
            
            // 如果是函数型的，则不进行前台验证，20181201 fgf 仍改为前台进验证
            if (ff.isFunc()) {
            	sb.append("$('input[name=" + ff.getName() + "]').attr(\"readonly\",\"readonly\");\n");
            	// continue;
            }
            
			String js = getCheckFieldJS(pck, ff);
			// 不用宏控件作为livevalidation的onsubmit元素
			if (someNotNullFieldName.equals("")) {
				// 如果没有add过，则当设置 var automaticOn***Submit =
				// f_***.form.onsubmit时，会出错
				if (js.indexOf(".add(") != -1) {
					if (ff.getType().equals(FormField.TYPE_MACRO)) {	
		            	MacroCtlUnit mu = mm.getMacroCtlUnit(ff.getMacroType());						
			            if (mu!=null && mu.getNestType() != MacroCtlUnit.NEST_TYPE_NORMAIL) {            
							// 不能用嵌套表格作为onsubmit的元素
			            	ffSubmit = ff;
			            }
					}
					else {
						ffSubmit = ff;						
					}
					if (!ff.getType().equals(FormField.TYPE_MACRO)) {
						someNotNullFieldName = ff.getName();
					}
				}
			}
            sb.append(js);
        }
        
        // 如果未找到不是宏控件的适用于livevalidation检查的域
        if ("".equals(someNotNullFieldName)) {
        	if (ffSubmit!=null) {
        		someNotNullFieldName = ffSubmit.getName();
        	}
        }

        // 可能会出现someNotNullFieldName为空的情况，如：没有必填项，所以增加fieldNameFirst
        if (!someNotNullFieldName.equals("")) {
            sb.append("var automaticOn" + someNotNullFieldName + "Submit = " +
                      "f_" + someNotNullFieldName + ".form.onsubmit;\n");
            sb.append("f_" + someNotNullFieldName + ".form.onsubmit = function() {\n");
            sb.append("var valid = automaticOn" + someNotNullFieldName + "Submit();\n");
            sb.append("if(valid)\n");
            sb.append("        return true;\n");
            sb.append("else\n");
            sb.append("        return false;\n");
            sb.append("}\n");
        }

        sb.append("</script>\n");
        return sb.toString();
    }

    /**
     * 根据表单的属性成生成规则字符串，用以进行有效性验证
     * @param ff FormField
     * @return String
     */
    public static String getCheckFieldJS(ParamChecker pck, FormField ff) {
        String fieldName = ff.getName();

        String ruleStr = "";
        String cNull = "not";
        if (ff.isCanNull()) {
            cNull = "empty";
        }

        // 置宏控件的数据类型fieldType，实际上2.2及之前版本均为varchar
        // 由于宏控件的值有些是通过程序动态获得的，所以不宜通过ParamChecker来进行有效性验证，因此在表单设计的时候，只设置其是否必填，而不设置最大和最小值
        // 在仅设置必填项的时候也有问题，如：意见框，用户可以先保存意见，然后再转交下一步，但是因为转交时设置了必填，就要重复填写意见，这就需要管理员设置的时候要仔细
        if (ff.getType().equals(FormField.TYPE_MACRO)) {
            // LogUtil.getLog(getClass()).info("saveDAO1 " + fieldName() + " getType()=" + getType() + " getMacroType=" + this.getMacroType());
            MacroCtlMgr mm = new MacroCtlMgr();
            MacroCtlUnit mu = mm.getMacroCtlUnit(ff.getMacroType());
            if (mu!=null) {
                IFormMacroCtl ifmctl = mu.getIFormMacroCtl();
                if (ifmctl != null)
                    ff.setFieldType(mu.getIFormMacroCtl().getFieldType());
                else
                    LogUtil.getLog(FormUtil.class).error("getCheckFieldJS: 宏控件" +
                            ff.getMacroType() + "不存在！");
            }
            else {
                LogUtil.getLog(FormUtil.class).error("getCheckFieldJS2: 宏控件" +
                            ff.getMacroType() + "不存在！");
            }
        }

        StringBuffer sb = new StringBuffer();

        if (ff.getFieldType() == FormField.FIELD_TYPE_VARCHAR) {
            ruleStr = ParamChecker.TYPE_STRING;
            ruleStr += "," + fieldName + "," +
                    ff.getTitle() + "," + cNull;
            if (!ff.getRule().equals("")) {
                ruleStr += "," + ff.getRule();
            }
            LogUtil.getLog(FormUtil.class).info("ruleStr=" + ruleStr);
            sb.append(pck.getCheckJSOfFieldString(ruleStr));
        } else if (ff.getFieldType() == FormField.FIELD_TYPE_TEXT) {
            ruleStr = ParamChecker.TYPE_STRING;

            ruleStr += "," + fieldName + "," +
                    ff.getTitle() + "," + cNull;
            if (!ff.getRule().equals("")) {
                ruleStr += "," + ff.getRule();
            }

            sb.append(pck.getCheckJSOfFieldString(ruleStr));
        }
        else if (ff.getFieldType() == FormField.FIELD_TYPE_INT) {
            if (ff.isCanNull()) {
                cNull = "allow";
            }
            ruleStr = ParamChecker.TYPE_INT;

            ruleStr += "," + fieldName + "," +
                    ff.getTitle() + "," + cNull;
            if (!ff.getRule().equals("")) {
                ruleStr += "," + ff.getRule();
            }
            sb.append(pck.getCheckJSOfFieldInt(ruleStr));
        }
        else if (ff.getFieldType() == FormField.FIELD_TYPE_LONG) {
            if (ff.isCanNull()) {
                cNull = "allow";
            }
            ruleStr = ParamChecker.TYPE_LONG;

            ruleStr += "," + fieldName + "," +
                    ff.getTitle() + "," + cNull;
            if (!ff.getRule().equals("")) {
                ruleStr += "," + ff.getRule();
            }

            sb.append(pck.getCheckJSOfFieldLong(ruleStr));
        }
        else if (ff.getFieldType() == FormField.FIELD_TYPE_BOOLEAN) {
            ruleStr = ParamChecker.TYPE_BOOLEAN;

            ruleStr += "," + fieldName + "," +
                    ff.getTitle() + "," + cNull;
            if (!ff.getRule().equals("")) {
                ruleStr += "," + ff.getRule();
            }

            sb.append(pck.getCheckJSOfFieldBoolean(ruleStr));
        }
        else if (ff.getFieldType() == FormField.FIELD_TYPE_FLOAT) {
            if (ff.isCanNull()) {
                cNull = "allow";
            }
            ruleStr = ParamChecker.TYPE_FLOAT;

            ruleStr += "," + fieldName + "," +
                    ff.getTitle() + "," + cNull;
            if (!ff.getRule().equals("")) {
                ruleStr += "," + ff.getRule();
            }
            sb.append(pck.getCheckJSOfFieldFloat(ruleStr));
        }
        else if (ff.getFieldType() == FormField.FIELD_TYPE_DOUBLE) {
            if (ff.isCanNull()) {
                cNull = "allow";
            }
            ruleStr = ParamChecker.TYPE_DOUBLE;

            ruleStr += "," + fieldName + "," +
                    ff.getTitle() + "," + cNull;
            if (!ff.getRule().equals("")) {
                ruleStr += "," + ff.getRule();
            }
            sb.append(pck.getCheckJSOfFieldDouble(ruleStr));
        }
        else if (ff.getFieldType() == FormField.FIELD_TYPE_DATE) {
            if (ff.isCanNull()) {
                cNull = "allow";
            }
            ruleStr = ParamChecker.TYPE_DATE;

            ruleStr += "," + fieldName + "," +
                    ff.getTitle() + "," + cNull;
            if (!ff.getRule().equals("")) {
                ruleStr += "," + ff.getRule();
            }
            ruleStr += ",format=yyyy-MM-dd";
            sb.append(pck.getCheckJSOfFieldDate(ruleStr));
        }
        else if (ff.getFieldType() == FormField.FIELD_TYPE_DATETIME) {
            if (ff.isCanNull()) {
                cNull = "allow";
            }
            ruleStr = ParamChecker.TYPE_DATE;

            ruleStr += "," + fieldName + "," +
                    ff.getTitle() + "," + cNull;
            if (!ff.getRule().equals("")) {
                ruleStr += "," + ff.getRule();
            }
            // 因为pck是根据规则从fu从取出ff的值，故只能取出日期部分，而不能取出时间部分，所以不能用format=yyyy-MM-dd HH:mm:ss，而应用format=yyyy-MM-dd
            // ruleStr += ",format=yyyy-MM-dd HH:mm:ss";
            ruleStr += ",format=yyyy-MM-dd";

            sb.append(pck.getCheckJSOfFieldDate(ruleStr));

            // 时间部分不再使用,也不再做判断
            /*
            if (cNull.equals("not")) {
                ruleStr = ParamChecker.TYPE_STRING;
                ruleStr += "," + fieldName + "_time," +
                        ff.getTitle() + "时间," + cNull;
                LogUtil.getLog(FormUtil.class).info("ruleStr=" + ruleStr);
                sb.append(pck.getCheckJSOfFieldString(ruleStr));
            }*/
        }
        else if (ff.getFieldType() == FormField.FIELD_TYPE_PRICE) {
            if (ff.isCanNull()) {
                cNull = "allow";
            }
            ruleStr = ParamChecker.TYPE_DOUBLE;

            ruleStr += "," + fieldName + "," +
                    ff.getTitle() + "," + cNull;
            if (!ff.getRule().equals("")) {
                ruleStr += "," + ff.getRule();
            }

            sb.append(pck.getCheckJSOfFieldDouble(ruleStr));
        }

        return sb.toString();
    }
    
    /**
     * 解析字符串，将其中的表单域替换成表单值
     * {$表单域的编码或者名称}
     * 先根据编码替换，不行再找名称，防止名称重复
     * @param strWithFields 含有表单域的字符串
     * @param ifdao
     * @return
     */
    public static String parseAndSetFieldValue(String strWithFields, IFormDAO ifdao) {
    	FormDb fd = ifdao.getFormDb();
        Pattern p = Pattern.compile(
                "\\{\\$([A-Z0-9a-z-_\\u4e00-\\u9fa5\\xa1-\\xff]+)\\}", // 前为utf8中文范围，后为gb2312中文范围
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(strWithFields);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String fieldTitle = m.group(1);
            // 制作大亚表单时发现，差旅费报销单中字段名称会有重复，所以这里先找编码，不行再找名称，防止名称重复
            FormField field = fd.getFormField(fieldTitle);
            if (field == null) {
                field = fd.getFormFieldByTitle(fieldTitle);
                if (field == null) {
                    LogUtil.getLog(FormUtil.class).error("表单：" + fd.getName() + "，脚本：" + strWithFields + "中，字段：" + fieldTitle + " 不存在！");
                }
            }
            
            if (field==null) {
            	LogUtil.getLog(FormUtil.class).error("parseAndSetFieldValue:" + fieldTitle + " 不存在");
            	// m.appendReplacement(sb, fieldTitle + " 不存在");
            	return ERROR_PARSE;
            }
            else {
            	// System.out.println(FormUtil.class + " ifdao.getFieldValue(field.getName())=" + ifdao.getFieldValue(field.getName()) + " fieldTitle=" + fieldTitle);
            	String val = StrUtil.getNullStr(ifdao.getFieldValue(field.getName()));
            	if (field.getType().equals(FormField.TYPE_MACRO)) {
	            	// 如果是宏控件，跳过默认值（如：SQL宏控件）
	            	if (val.equals(field.getDefaultValueRaw())) {
	            		val = "";
	            	}
            	}
            	m.appendReplacement(sb, val);
            }
        }
        m.appendTail(sb);

        return sb.toString();
    }

    /**
     * 取出嵌套表中需要sum的字段，用于传至手机端
     * @param fdRelated
     * @param fdParent
     * @param cwsId
     * @return
     */
	public static JSONObject getSums(FormDb fdRelated, FormDb fdParent, String cwsId) {
		JSONObject sums = new JSONObject();
		// 遍历主表中的公式，取出所有用到的sum(nest.***)字段
		Iterator ir = fdParent.getFields().iterator();
		while (ir.hasNext()) {
			FormField ff = (FormField)ir.next();
			if (ff.getType().equals(FormField.TYPE_CALCULATOR)) {
				String formula = ff.getDefaultValueRaw();
				// 解析其中的sum
				Pattern p = Pattern.compile(
						"sum\\((.*?)\\)", // 前为utf8中文范围，后为gb2312中文范围
						Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
				Matcher m = p.matcher(formula);
				while (m.find()) {
					String nestFieldName = m.group(1);
					if (nestFieldName.startsWith("nest.")) {
						nestFieldName = nestFieldName.substring(5);
						
						FormField nestField = fdRelated.getFormField(nestFieldName);
						if (nestField==null) {
							continue;
						}

						String sql = "select sum(" + nestFieldName + ") from form_table_" + fdRelated.getCode() + " where cws_id=" + StrUtil.sqlstr(cwsId);
						JdbcTemplate jt = new JdbcTemplate();
						ResultIterator ri;
						try {
							ri = jt.executeQuery(sql);
							if (ri.hasNext()) {
								ResultRecord rr = (ResultRecord)ri.next();
								double val = rr.getDouble(1);
								try {
									sums.put(nestFieldName, String.valueOf(val));
								} catch (JSONException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}
							}
							else {
								try {
									sums.put(nestFieldName, "0");
								} catch (JSONException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}
							}
						} catch (SQLException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}
			}					
		}
		
		return sums;
	}    

}
