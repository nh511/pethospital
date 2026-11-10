<script setup lang="ts">
import {ref,onMounted} from 'vue';import axios from 'axios';
const state=ref('正在连接后端');const hospital=ref('');
onMounted(async()=>{try{const r=await axios.get('/api/v1/hospital');hospital.value=r.data.data.name;state.value='后端连接正常'}catch{state.value='后端未启动，请启动 server 后刷新'}});
</script>
<template><main><header><span>✚ 宠物医疗</span><small>医生 · 管理员工作台</small></header><section><div class="eyebrow">PROJECT FOUNDATION</div><h1>{{ hospital || '宠物医院工作台' }}</h1><p>从预约、诊疗到长期健康管理</p><el-alert :title="state" :closable="false" type="info"/><p class="hint">当前为工程基础页面。账号、预约、病历与知识库模块正在按任务清单实施，尚未开放业务入口。</p></section></main></template>
<style>
body{margin:0;background:#f5f7f6;color:#243d35;font-family:system-ui,'Microsoft YaHei',sans-serif}header{padding:24px 5%;display:flex;justify-content:space-between;border-bottom:1px solid #dce6df;background:#fff}header span{font-weight:700;font-size:22px}small{color:#74857b}section{max-width:850px;margin:80px auto;background:white;padding:48px;border-radius:16px;border:1px solid #e1e9e4}h1{font-size:38px;margin:14px 0}p{line-height:1.8;color:#607268}.eyebrow{letter-spacing:3px;color:#338569;font-size:12px}.hint{font-size:14px;margin-top:24px}
</style>

