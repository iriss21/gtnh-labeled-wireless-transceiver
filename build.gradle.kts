plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

dependencies {
    // 本地 AE2（GTNH 分支 rv3-beta）dev jar，SRG 映射，直接以 compileOnly 消费，无需 rfg.deobf。
    // GTNH Maven 上该坐标 404，故放在 libs/ 下手动引用。
    compileOnly(files("libs/appliedenergistics2-rv3-beta-695-GTNH.jar"))
}
