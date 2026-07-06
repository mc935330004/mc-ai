CREATE TABLE ai_capability_definition (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  capability_code VARCHAR(128) NOT NULL UNIQUE COMMENT '能力编码，例如 pm.project.getByName',
  capability_name VARCHAR(128) NOT NULL COMMENT '能力名称',
  domain VARCHAR(64) NOT NULL COMMENT '业务域，例如 pm/contract/payment',
  module_name VARCHAR(64) COMMENT '模块名称',
  description VARCHAR(512) COMMENT '能力说明，给开发人员和大模型理解用途',
  method VARCHAR(16) NOT NULL COMMENT '请求方法，例如 GET/POST',
  url VARCHAR(256) NOT NULL COMMENT '真实业务接口地址',
  side_effect VARCHAR(32) NOT NULL DEFAULT 'READ' COMMENT '副作用级别：READ/WRITE/DANGEROUS',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1启用，0停用',
  input_schema_json TEXT COMMENT '入参 JSON Schema',
  output_schema_json TEXT COMMENT '出参 JSON Schema',
  example_json TEXT COMMENT '调用示例 JSON',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT='AI能力定义表';

CREATE TABLE ai_field_dictionary (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
 capability_code VARCHAR(128) NOT NULL COMMENT '能力编码',
 field_path VARCHAR(256) NOT NULL COMMENT '字段路径，例如 $.data.contractAmount',
 field_name VARCHAR(128) NOT NULL COMMENT '字段英文名',
 field_cn_name VARCHAR(128) NOT NULL COMMENT '字段中文名',
 field_type VARCHAR(64) COMMENT '字段类型，例如 string/number/date',
 business_meaning VARCHAR(512) COMMENT '业务含义',
 display_format VARCHAR(128) COMMENT '展示格式，例如 amount/date/percent',
 example_value VARCHAR(256) COMMENT '示例值',
 searchable TINYINT DEFAULT 0 COMMENT '是否可搜索',
 aggregatable TINYINT DEFAULT 0 COMMENT '是否可聚合统计',
 created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT='AI字段语义字典表';