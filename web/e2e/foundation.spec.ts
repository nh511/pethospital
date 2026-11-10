import {test,expect} from '@playwright/test';
test('foundation renders and reports unavailable backend honestly',async({page})=>{
 await page.goto('/');
 await expect(page.getByRole('heading',{level:1})).toContainText('宠物医院');
 await expect(page.getByText('医生 · 管理员工作台')).toBeVisible();
 await expect(page.getByRole('alert')).toContainText('后端');
 await expect(page.getByText('当前为工程基础页面。',{exact:false})).toBeVisible();
});
