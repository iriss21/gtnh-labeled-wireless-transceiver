plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

dependencies {
    // AE2（GTNH 分支 rv3-beta-695-GTNH）：从 GTNH Maven 拉取。
    // 该坐标 release jar 与 GTNH 实例 mods 目录中的
    // appliedenergistics2-rv3-beta-695-GTNH.jar 完全一致（SHA256 相同），
    // 编译期消费即可；运行时由 GTNH 实例提供，不入包。
    compileOnly("com.github.GTNewHorizons:Applied-Energistics-2-Unofficial:rv3-beta-695-GTNH")
}
